package com.machankura.bennu.compose.ui.view.model

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
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.ui.view.state.GroupWalletUIState
import com.machankura.bennu.compose.wallet.GroupChainWallets
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The group's wallet, read once for what the group has signed and then watched
 * for what the chain says.
 *
 * The statement is a read, like every other group-signed thing's screen; the
 * coins are a watch, because a payment arriving while the screen is open is the
 * one moment somebody is looking at it.
 *
 * [wallets] is null for a profile with no node of its own -- an nsec or an npub
 * -- which can see that the group keeps a wallet, and cannot see the chain.
 */
class GroupWalletViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialGroupWalletUIState: GroupWalletUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
    private val wallets: GroupChainWallets?,
): ViewModel() {
    private val logger = Logger.withTag(TAG)

    var groupWalletUIState: GroupWalletUIState by mutableStateOf(initialGroupWalletUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                groupWalletUIState = GroupWalletUIState.Error("Couldn't find the group")
                return@launch
            }

            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            val wallet = chatRepository.groupWallet(chatRoomId, chain)

            if (wallet == null) {
                groupWalletUIState = GroupWalletUIState.NotOpened(
                    localChatRoom = localChatRoom,
                    account = chatRepository.groupWalletAccount(chatRoomId, chain),
                    canSign = canSign,
                )
                return@launch
            }

            val loaded = GroupWalletUIState.Loaded(
                localChatRoom = localChatRoom,
                wallet = wallet,
                walletState = null,
                canWatch = wallets != null,
                canSign = canSign,
            )
            groupWalletUIState = loaded

            val chainWallet = wallets?.walletFor(wallet.account) ?: return@launch
            watching?.cancel()
            watching = viewModelScope.launch(Dispatchers.IO) {
                chainWallet.state.collect { state ->
                    groupWalletUIState = loaded.copy(walletState = state)
                }
            }
        }
    }

    /**
     * Asks the group to open its wallet: one signing session over one statement.
     *
     * Never batched with anything, for the reason `GroupWalletStatementEvent`
     * gives -- every spend is opened against this, and it should never be as
     * available as its worst co-passenger.
     */
    fun openWallet(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = groupWalletUIState as? GroupWalletUIState.NotOpened ?: return
        val account = state.account ?: return
        // A second statement for the same account is harmless -- the same values
        // under the same `d` tag -- but it is a second quorum asked for nothing.
        if (isActionPending.value) return
        isActionPending.value = true

        val template = GroupWalletStatementEvent.template(account)

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = state.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a wallet for $chatRoomId", error)
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (session != null) onSuccess(session.id) else onFailure()
            }

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "GroupWalletViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            initialGroupWalletUIState: GroupWalletUIState = GroupWalletUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
            wallets: GroupChainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupWalletViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    chain = chain,
                    initialGroupWalletUIState = initialGroupWalletUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                    wallets = wallets,
                )
            }
        }
    }
}
