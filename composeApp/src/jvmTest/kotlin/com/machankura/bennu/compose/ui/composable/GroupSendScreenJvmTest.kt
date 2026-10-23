package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.state.GroupSendUIState
import com.machankura.bennu.compose.wallet.GroupWalletState
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
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The send form, typed into: an amount is read back as the preview will say
 * it, a problem with the amount is said on the amount field, and
 * send-everything puts the field away without forgetting what was in it.
 */
@OptIn(ExperimentalTestApi::class)
class GroupSendScreenJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val regtestAddress = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
    private val mainnetAddress = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

    private val isError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    @Test
    fun `an amount is read back grouped, and the spend it builds can be proposed`() = render(Chain.Regtest) {
        fill(regtestAddress, amount = "40000")

        onNodeWithText("40,000").assertIsDisplayed()
        onNodeWithText("Sends 40,000 sats", substring = true).assertIsDisplayed()
        onNodeWithText("come back to the group as change", substring = true).assertIsDisplayed()
        onNodeWithText("Ask the group to send").assertIsEnabled()
    }

    @Test
    fun `how much can be spent is said under the amount`() = render(Chain.Regtest) {
        onNodeWithText("100,000 sats confirmed and free to spend").assertIsDisplayed()
        onNodeWithText("While the wallet is new", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a decimal is refused on the amount field, and nothing can be proposed`() = render(Chain.Regtest) {
        fill(regtestAddress, amount = "0.5")

        onNodeWithText("Sats are whole numbers", substring = true).assertIsDisplayed()
        onNodeWithText("Amount in sats").assert(isError)
        onNodeWithText("Sends", substring = true).assertDoesNotExist()
        onNodeWithText("Ask the group to send").assertIsNotEnabled()
    }

    @Test
    fun `send everything puts the amount away, and turning it off finds it again`() = render(Chain.Regtest) {
        fill(regtestAddress, amount = "40000")

        onNodeWithText("Send everything").performClick()
        onNodeWithText("Amount in sats").assertDoesNotExist()
        onNodeWithText("come back to the group as change", substring = true).assertDoesNotExist()

        onNodeWithText("Send everything").performClick()
        onNodeWithText("40,000").assertIsDisplayed()
        onNodeWithText("Sends 40,000 sats", substring = true).assertIsDisplayed()
    }

    @Test
    fun `on mainnet the cap is said before it is hit, and on the field when it is`() = render(Chain.Mainnet) {
        val cap = "While the wallet is new, a spend on mainnet can send at most 100,000 sats."
        onNodeWithText(cap).assertIsDisplayed()

        fill(mainnetAddress, amount = "150000")

        onNodeWithText(cap).assertIsDisplayed()
        onNodeWithText("Amount in sats").assert(isError)
        onNodeWithText("Ask the group to send").assertIsNotEnabled()
    }

    private fun ComposeUiTest.fill(address: String, amount: String) {
        onNodeWithText("Address").performTextInput(address)
        onNodeWithText("Fee rate in sats per byte").performTextInput("4")
        onNodeWithText("Amount in sats").performTextInput(amount)
    }

    private fun ready(chain: Chain): GroupSendUIState.Ready {
        val account = GroupWalletDerivation.account(
            PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(), chain,
        )
        val wallet = GroupWallet(
            GroupSignedEvent(
                id = "e".repeat(64), chatRoomId = chatRoomId, publicKey = chatRoomId, kind = GroupWalletStatementEvent.KIND,
                tags = GroupWalletStatementEvent.template(account).tags, content = account.xpub, signature = "f".repeat(128),
                createdAt = Instant.fromEpochSeconds(1_700_000_000),
            ),
            account,
        )
        val receive = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
        val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        val funding = Transaction(
            2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 1 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(if (chain.isMainnet()) 300_000 else 100_000), receive.pubkeyScript)), 0,
        )
        val meta = WalletState.AddressMeta.Derived(0)
        val walletState = GroupWalletState(
            account,
            WalletState(mapOf(receive.address(chain) to WalletState.AddressState(meta, true, listOf(WalletState.Utxo(funding.txid, 0, 100, funding, meta))))),
            WalletState(mapOf(change.address(chain) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList()))),
            tipHeight = 110,
        )
        val room = LocalChatRoom(
            chatRoom = ChatRoom(
                id = chatRoomId, userPublicKey = "a".repeat(64), subject = "Treasury", description = null,
                initialGiftWrapPayloadId = "p", mlsGroupState = "state",
            )
        )
        return GroupSendUIState.Ready(room, wallet, walletState, reserved = emptySet())
    }

    private fun render(chain: Chain, assertions: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 1200) {
            setContent {
                BennuTheme {
                    ProvideSnackbarHost {
                        Box(modifier = Modifier.fillMaxSize()) {
                            GroupSendScreen(
                                activeUserPublicKey = "a".repeat(64),
                                chatRoomId = chatRoomId,
                                chain = chain,
                                initialGroupSendUIState = ready(chain),
                                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                                wallets = null,
                                onNavigateToRouteAndPopUpInclusive = {},
                                onNavigateBack = {},
                            )
                        }
                    }
                }
            }

            assertions()
        }
}
