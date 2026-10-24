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
import com.machankura.bennu.compose.repository.MemberWalletRepository
import com.machankura.bennu.compose.ui.view.state.MemberWalletUIState
import com.machankura.bennu.compose.ui.view.state.TakenLine
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One of the member's guarded wallets: read once for where the asking has got
 * to, then -- for a certified wallet on a profile that can watch the chain --
 * watched for its coins, and searched for what its guardians took.
 *
 * Addresses are offered only once a group has certified the wallet: a wallet
 * nobody has agreed to guard should not be handed out to be paid.
 */
class MemberWalletViewModel(
    private val ownerPublicKey: HexKey,
    val walletId: String,
    initialUIState: MemberWalletUIState,
    private val memberWalletRepository: MemberWalletRepository,
    private val wallets: GroupOnchainWallets?,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: MemberWalletUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** The exported descriptors while they are being shown, and only then. */
    var exported: Pair<String, String>? by mutableStateOf(null)
        private set

    private var watching: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = memberWalletRepository.walletOf(ownerPublicKey, walletId)
            if (wallet == null) {
                uiState = MemberWalletUIState.Error("Couldn't find this wallet")
                return@launch
            }
            val loaded = MemberWalletUIState.Loaded(wallet = wallet, canWatch = wallets != null)
            uiState = loaded

            watching?.cancel()
            if (!wallet.isCertified || wallets == null) return@launch
            watching = viewModelScope.launch(Dispatchers.IO) {
                var taken = emptyList<TakenLine>()
                wallets.walletFor(wallet.account).state
                    .distinctUntilChangedBy { state -> state.utxos.map { it.utxo.outPoint } to state.tipHeight }
                    .collect { state ->
                        uiState = loaded.copy(walletState = state, taken = taken)
                        // History, not a watch: a device asleep when a guardian took a
                        // coin still finds it, since the chain keeps the record.
                        taken = wallets.guardianSpends(state).map { spend ->
                            TakenLine(spend.txid.toString(), memberWalletRepository.roomName(spend.guardian.chatRoomId), spend.amount)
                        }
                        uiState = loaded.copy(walletState = state, taken = taken)
                    }
            }
        }
    }

    fun askAgain(onAsked: () -> Unit, onFailure: () -> Unit) = act(onAsked, onFailure) {
        memberWalletRepository.askAgain(walletId)
        initiate()
    }

    fun discard(onDiscarded: () -> Unit, onFailure: () -> Unit) = act(onDiscarded, onFailure) {
        memberWalletRepository.discard(walletId)
    }

    fun exportKey() {
        viewModelScope.launch(Dispatchers.IO) { exported = memberWalletRepository.exportKey(walletId) }
    }

    fun hideKey() {
        exported = null
    }

    private fun act(onDone: () -> Unit, onFailure: () -> Unit, action: suspend () -> Unit) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val done = runCatching { action() }.onFailure { logger.e("Couldn't act on wallet $walletId", it) }.isSuccess
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (done) onDone() else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "MemberWalletViewModel"

        fun factory(
            ownerPublicKey: HexKey,
            walletId: String,
            initialUIState: MemberWalletUIState = MemberWalletUIState.Loading,
            memberWalletRepository: MemberWalletRepository,
            wallets: GroupOnchainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemberWalletViewModel(ownerPublicKey, walletId, initialUIState, memberWalletRepository, wallets) }
        }
    }
}
