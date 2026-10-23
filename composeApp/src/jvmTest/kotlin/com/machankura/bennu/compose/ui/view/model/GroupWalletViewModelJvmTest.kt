package com.machankura.bennu.compose.ui.view.model

import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.FrostSigningSession
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.ui.view.state.GroupWalletUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * What the wallet screen's view model decides: which of its states a room is in,
 * and what it asks the group to sign when a member opens a wallet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val me = "a".repeat(64)

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = me,
            subject = "Treasury",
            description = null,
            initialGiftWrapPayloadId = "payload",
            mlsGroupState = "state",
        )
    )

    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
        Chain.Mainnet,
    )

    /** The repository's answer is the thing under test's input; the statement's own checks are `GroupWalletStatementEventTest`'s. */
    private val wallet = GroupWallet(
        signedEvent = GroupSignedEvent(
            id = "e".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = GroupWalletStatementEvent.KIND,
            tags = GroupWalletStatementEvent.template(account).tags,
            content = account.xpub,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        account = account,
    )

    private fun repository(opened: GroupWallet?, canSign: Boolean = true, hasKey: Boolean = true) =
        object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = localChatRoom
            override suspend fun canSign(chatRoomId: String): Boolean = canSign
            override suspend fun groupWallet(chatRoomId: String, chain: Chain): GroupWallet? = opened
            override suspend fun groupWalletAccount(chatRoomId: String, chain: Chain): GroupWalletDerivation.Account? =
                account.takeIf { hasKey }
        }

    private class RecordingSigning : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var proposed: Triple<Kind, Array<Array<String>>, String>? = null

        override suspend fun proposeSigning(
            localChatRoom: LocalChatRoom,
            userPublicKey: HexKey,
            kind: Kind,
            tags: Array<Array<String>>,
            content: String,
        ): FrostSigningSession? {
            proposed = Triple(kind, tags, content)
            return null
        }
    }

    private fun viewModel(chat: ChatRepository, signing: FrostSigningRepository = RecordingSigning()) =
        GroupWalletViewModel(
            activeUserPublicKey = me,
            chatRoomId = chatRoomId,
            chain = Chain.Mainnet,
            initialGroupWalletUIState = GroupWalletUIState.Loading,
            chatRepository = chat,
            frostSigningRepository = signing,
            wallets = null,
        )

    private fun GroupWalletViewModel.settled(): GroupWalletUIState = runBlocking {
        withTimeout(5_000) {
            while (groupWalletUIState == GroupWalletUIState.Loading) delay(10)
            groupWalletUIState
        }
    }

    @Test
    fun `a group that has not opened a wallet is offered the account it would open`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(viewModel(repository(opened = null)).apply { initiate() }.settled())

        assertEquals(account, state.account)
        assertEquals(true, state.canSign)
    }

    @Test
    fun `a room with no key has nothing to open`() {
        val state = assertIs<GroupWalletUIState.NotOpened>(
            viewModel(repository(opened = null, hasKey = false)).apply { initiate() }.settled()
        )

        assertNull(state.account)
    }

    @Test
    fun `an open wallet on a profile with no node is shown, and says it cannot watch`() {
        val state = assertIs<GroupWalletUIState.Loaded>(viewModel(repository(opened = wallet)).apply { initiate() }.settled())

        assertEquals(wallet, state.wallet)
        assertFalse(state.canWatch)
        assertNull(state.walletState)
    }

    @Test
    fun `opening a wallet proposes the statement for the account, and nothing else`() {
        val signing = RecordingSigning()
        val model = viewModel(repository(opened = null), signing).apply { initiate() }
        model.settled()

        var failed = false
        model.openWallet(onSuccess = {}, onFailure = { failed = true })
        runBlocking { withTimeout(5_000) { while (signing.proposed == null || model.isActionPending.value) delay(10) } }

        val (kind, tags, content) = assertNotNull(signing.proposed)
        assertEquals(GroupWalletStatementEvent.KIND, kind)
        assertEquals(account.xpub, content)
        assertEquals(account, GroupWalletStatementEvent.read(tags, content)?.verifiedAgainst(
            PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString()
        ))
        runBlocking { withTimeout(5_000) { while (!failed) delay(10) } }
    }
}
