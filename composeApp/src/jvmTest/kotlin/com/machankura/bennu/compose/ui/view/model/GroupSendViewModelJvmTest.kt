package com.machankura.bennu.compose.ui.view.model

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.FrostSigningSession
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.text.SatsInput
import com.machankura.bennu.compose.ui.view.state.GroupSendUIState
import com.machankura.bennu.compose.wallet.GroupSpend
import com.machankura.bennu.compose.wallet.GroupSpendBuilder
import com.machankura.bennu.compose.wallet.GroupWalletState
import com.machankura.bennu.compose.wallet.GroupWalletRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.lightning.blockchain.electrum.WalletState
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The send form: what it previews is what it proposes, and it is not offered to
 * a device that cannot both sign and see the coins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "Treasury", description = null, initialGiftWrapPayloadId = "p", mlsGroupState = "state")
    )
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(), Chain.Regtest,
    )
    private val wallet = GroupWallet(
        GroupSignedEvent(
            id = "e".repeat(64), chatRoomId = chatRoomId, publicKey = chatRoomId, kind = GroupWalletStatementEvent.KIND,
            tags = GroupWalletStatementEvent.template(account).tags, content = account.xpub, signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        account,
    )

    private val walletState: GroupWalletState = run {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 1 })), 0), ByteVector.empty, 0xffffffffL)), listOf(TxOut(Satoshi(100_000), derived.pubkeyScript)), 0)
        val meta = WalletState.AddressMeta.Derived(0)
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        GroupWalletState(
            account,
            WalletState(mapOf(derived.address(Chain.Regtest) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(tx.txid, 0, 100, tx, meta))))),
            WalletState(mapOf(change.address(Chain.Regtest) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList()))),
            tipHeight = 110,
        )
    }

    private class RecordingSigning : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var proposed: Psbt? = null
        override suspend fun proposeSpend(localChatRoom: LocalChatRoom, userPublicKey: HexKey, psbt: Psbt, chain: Chain): FrostSigningSession? {
            proposed = psbt
            return null
        }
    }

    private fun viewModel(initial: GroupSendUIState, chat: ChatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, signing: FrostSigningRepository = RecordingSigning()) =
        GroupSendViewModel("a".repeat(64), chatRoomId, Chain.Regtest, initial, chat, signing, wallets = null)

    @Test
    fun `a profile with no node is not offered the form`() {
        val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(identifier: String) = localChatRoom
            override suspend fun canSign(chatRoomId: String) = true
            override suspend fun groupWallet(chatRoomId: String, chain: Chain) = wallet
        }
        val model = viewModel(GroupSendUIState.Loading, chat).apply { initiate() }

        runBlocking { withTimeout(5_000) { while (model.groupSendUIState == GroupSendUIState.Loading) delay(10) } }
        assertEquals(GroupSendUIState.Unavailable, model.groupSendUIState)
    }

    @Test
    fun `the preview waits for the form, and what it builds is what is proposed`() {
        val signing = RecordingSigning()
        val model = viewModel(GroupSendUIState.Ready(localChatRoom, wallet, walletState, reserved = emptySet<OutPoint>()), signing = signing)

        assertNull(model.preview(), "nothing to build before an address and a feerate")

        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.amountField.setTextAndPlaceCursorAtEnd("40,000")
        val built = assertIs<GroupSpendBuilder.Result.Built>(model.preview())
        if (GroupWalletRollout.SEND_AN_AMOUNT) {
            assertEquals(Satoshi(40_000), built.amount)
            assertNotNull(built.change)
        } else {
            // Gated to send-all: the amount typed is not what is sent.
            assertEquals(Satoshi(100_000), built.amount + built.fee)
            assertNull(built.change)
        }

        model.propose(onSuccess = {}, onFailure = {})
        runBlocking { withTimeout(5_000) { while (signing.proposed == null) delay(10) } }
        assertEquals(built.psbt, signing.proposed)
        assertIs<GroupSpend.Result.Ok>(GroupSpend.check(signing.proposed!!, account), "the other members would accept it")
    }

    @Test
    fun `a decimal amount is refused, never sent ten times over`() {
        val model = viewModel(GroupSendUIState.Ready(localChatRoom, wallet, walletState, reserved = emptySet()))
        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.amountField.setTextAndPlaceCursorAtEnd("21000.5")

        assertEquals(SatsInput.NotWhole, model.amount())
        val preview = model.preview()
        assertNotEquals(Satoshi(210_005), (preview as? GroupSpendBuilder.Result.Built)?.amount)
        if (GroupWalletRollout.SEND_AN_AMOUNT) {
            assertEquals(GroupSpendBuilder.Result.InvalidAmount(SatsInput.NotWhole), preview)
        }
    }

    @Test
    fun `send everything ignores the amount`() {
        val model = viewModel(GroupSendUIState.Ready(localChatRoom, wallet, walletState, reserved = emptySet()))
        model.addressField.setTextAndPlaceCursorAtEnd("bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080")
        model.feerateField.setTextAndPlaceCursorAtEnd("4")
        model.sendEverything.value = true

        val built = assertIs<GroupSpendBuilder.Result.Built>(model.preview())
        assertEquals(Satoshi(100_000), built.amount + built.fee)
        assertNull(built.change)
    }
}
