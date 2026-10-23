package com.machankura.bennu.compose.ui.view.model

import com.machankura.bennu.compose.wallet.GroupWalletRollout
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.text.SatsInput
import com.machankura.bennu.compose.text.parseSats
import com.machankura.bennu.compose.ui.view.state.GroupSendUIState
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import com.machankura.bennu.compose.wallet.GroupSpendBuilder
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A spend from the group's wallet, built as the member types and proposed when
 * they ask.
 *
 * The preview the screen shows is [preview] over the same inputs the proposal is
 * built from, so the fee a member reads before pressing the button is the fee
 * the group is then asked to sign -- there is no second build.
 */
class GroupSendViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialGroupSendUIState: GroupSendUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    private val wallets: GroupOnchainWallets?,
): ViewModel() {
    private val logger = Logger.withTag(TAG)

    var groupSendUIState: GroupSendUIState by mutableStateOf(initialGroupSendUIState)
        private set

    val addressField = TextFieldState()
    val amountField = TextFieldState()
    val feerateField = TextFieldState()
    val sendEverything: MutableState<Boolean> = mutableStateOf(false)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                groupSendUIState = GroupSendUIState.Error("Couldn't find the group")
                return@launch
            }

            val wallet = chatRepository.groupWallet(chatRoomId, chain)
            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            if (wallet == null || !canSign || wallets == null) {
                groupSendUIState = GroupSendUIState.Unavailable
                return@launch
            }

            val ready = GroupSendUIState.Ready(
                localChatRoom = localChatRoom,
                wallet = wallet,
                walletState = null,
                reserved = frostSigningRepository.reservedOutPoints(chatRoomId),
                reservedChange = frostSigningRepository.reservedChangeIndices(chatRoomId, wallet.account),
            )
            groupSendUIState = ready

            wallets.suggestedFeeratePerVbyte()?.let { suggested ->
                withContext(Dispatchers.Main) {
                    if (feerateField.text.isEmpty()) feerateField.setTextAndPlaceCursorAtEnd(suggested.toString())
                }
            }

            val onchainWallet = wallets.walletFor(wallet.account)
            watching?.cancel()
            watching = viewModelScope.launch(Dispatchers.IO) {
                onchainWallet.state.collect { state -> groupSendUIState = ready.copy(walletState = state) }
            }
        }
    }

    /** What the amount field says, read the one way everything that reads it does. */
    fun amount(): SatsInput = parseSats(amountField.text)

    /**
     * The spend the form describes right now, or null while it is incomplete --
     * no wallet state yet, no feerate, no amount. An amount that is not one is
     * not incomplete, and says so before the rest of the form is filled in.
     */
    fun preview(): GroupSpendBuilder.Result? {
        val state = groupSendUIState as? GroupSendUIState.Ready ?: return null
        val walletState = state.walletState ?: return null

        val amount = if (sendEverything.value) {
            null
        } else {
            when (val input = amount()) {
                SatsInput.Empty -> return null
                is SatsInput.Sats -> input.amount
                else -> return GroupSpendBuilder.Result.InvalidAmount(input)
            }
        }

        val feerate = feerateField.text.toString().trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        val address = addressField.text.toString().trim().takeIf { it.isNotEmpty() } ?: return null

        val built = if (amount == null) {
            GroupSpendBuilder.sendAll(walletState, address, feerate, state.reserved)
        } else {
            GroupSpendBuilder.send(walletState, address, amount, feerate, state.reserved, state.reservedChange)
        }
        return GroupSpendBuilder.capped(built, GroupWalletRollout.capFor(chain))
    }

    fun propose(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = groupSendUIState as? GroupSendUIState.Ready ?: return
        val built = preview() as? GroupSpendBuilder.Result.Built ?: return
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSpend(
                    localChatRoom = state.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    psbt = built.psbt,
                    chain = chain,
                )
            }.onFailure { logger.e("Failed to propose a spend from $chatRoomId", it) }.getOrNull()

            withContext(Dispatchers.Main) {
                if (session != null) onSuccess(session.id) else onFailure()
            }
            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "GroupSendViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            initialGroupSendUIState: GroupSendUIState = GroupSendUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            wallets: GroupOnchainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupSendViewModel(
                    activeUserPublicKey, chatRoomId, chain, initialGroupSendUIState,
                    chatRepository, frostSigningRepository, wallets,
                )
            }
        }
    }
}
