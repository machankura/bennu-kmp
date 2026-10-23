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
import com.machankura.bennu.compose.database.model.FrostSigningSession
import com.machankura.bennu.compose.database.model.types.IcebergRitualStage
import com.machankura.bennu.compose.identity.Identity
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.GroupLightningRepository
import com.machankura.bennu.compose.ui.view.state.GroupLightningWalletUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The group's Lightning wallet, read for what the room has agreed: whether it can have one yet, the
 * statement that opened it, and who runs its node. Every action is a proposal the group signs.
 */
class GroupLightningWalletViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupLightningWalletUIState,
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupLightningWalletUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                uiState = GroupLightningWalletUIState.Error("Couldn't find the group")
                return@launch
            }
            uiState = when {
                !lightningRepository.hasOnchainWallet(chatRoomId, chain) -> GroupLightningWalletUIState.NeedsOnchainWallet(localChatRoom)
                else -> {
                    val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
                    val key = lightningRepository.completedCeremony(chatRoomId)
                    when {
                        wallet != null -> {
                            val operator = lightningRepository.operator(chatRoomId, chain)
                            GroupLightningWalletUIState.Opened(
                                localChatRoom = localChatRoom,
                                wallet = wallet,
                                operator = operator,
                                isParticipant = activeUserPublicKey.lowercase() in wallet.statement.participants && wallet.ceremony?.share != null,
                                isOperator = operator?.publicKey == activeUserPublicKey,
                            )
                        }
                        key != null -> GroupLightningWalletUIState.NotOpened(
                            localChatRoom = localChatRoom,
                            key = key,
                            canPropose = lightningRepository.lspNodeId(chain) != null && activeIdentityStateFlow.value?.nostrPrivateKey != null,
                        )
                        else -> {
                            val running = lightningRepository.observeCeremonies(chatRoomId).first().firstOrNull { it.stage != IcebergRitualStage.FAILED }
                            GroupLightningWalletUIState.NeedsKey(localChatRoom, running, canMakeKey = lightningRepository.ceremonyThresholds(chatRoomId).isNotEmpty())
                        }
                    }
                }
            }
            if (uiState is GroupLightningWalletUIState.Opened) watchReports()
        }
    }

    private var watching: Job? = null

    /** Keeps an opened wallet's snapshot and payments current as the operator's reports arrive. */
    private fun watchReports() {
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            combine(lightningRepository.observeReport(chatRoomId, chain), lightningRepository.observePayments(chatRoomId, chain)) { report, payments -> report to payments }
                .collect { (report, payments) ->
                    val unswept = lightningRepository.unswept(chatRoomId, report)
                    (uiState as? GroupLightningWalletUIState.Opened)?.let { uiState = it.copy(report = report, payments = payments, unswept = unswept) }
                }
        }
    }

    /** Proposes the wallet's statement; the wallet exists once a quorum signs, on the session it returns. */
    fun openWallet(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.NotOpened ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        act(onSession, onFailure) { lightningRepository.proposeWallet(state.localChatRoom, activeUserPublicKey, privateKey, chain) }
    }

    fun nominateSelf(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        act(onSession, onFailure) { lightningRepository.nominateSelf(state.localChatRoom, activeUserPublicKey, chain) }
    }

    fun stepDown(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        act(onSession, onFailure) { lightningRepository.stepDown(state.localChatRoom, activeUserPublicKey, chain) }
    }

    /** Proposes closing [channelId] to the onchain wallet, or by force when the LSP is gone. */
    fun close(channelId: String, force: Boolean, onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        act(onSession, onFailure) { lightningRepository.proposeClose(state.localChatRoom, activeUserPublicKey, state.wallet, channelId, force) }
    }

    /** Proposes sweeping everything closed channels left the group into the onchain wallet. */
    fun sweep(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningWalletUIState.Opened ?: return
        if (state.unswept.isEmpty()) return
        act(onSession, onFailure) { lightningRepository.proposeSweep(state.localChatRoom, activeUserPublicKey, state.wallet, state.unswept) }
    }

    private fun act(onSession: (String) -> Unit, onFailure: () -> Unit, propose: suspend () -> FrostSigningSession) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { propose() }.onFailure { logger.e("Lightning wallet proposal in $chatRoomId failed", it) }.getOrNull()
            isActionPending.value = false
            withContext(Dispatchers.Main) { if (session != null) onSession(session.id) else onFailure() }
        }
    }

    companion object {
        private const val TAG = "GroupLightningWalletViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupLightningWalletViewModel(activeUserPublicKey, chatRoomId, chain, GroupLightningWalletUIState.Loading, activeIdentityStateFlow, chatRepository, lightningRepository)
            }
        }
    }
}
