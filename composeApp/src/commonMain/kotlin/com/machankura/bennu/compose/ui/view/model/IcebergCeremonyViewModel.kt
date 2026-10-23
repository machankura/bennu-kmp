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
import com.machankura.bennu.compose.database.model.types.IcebergRitualStage
import com.machankura.bennu.compose.identity.Identity
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.GroupLightningRepository
import com.machankura.bennu.compose.ui.view.state.IcebergCeremonyUIState
import com.machankura.bennu.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches a group's Lightning wallet key ceremony, opens one, and approves its steps.
 *
 * Nothing here drives the protocol: [com.machankura.bennu.compose.managers.IcebergRitualManager]
 * advances as messages arrive, and this only reads the session and asks its owner what it is waiting on.
 */
class IcebergCeremonyViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    /** The ceremony to show, when the caller knew which; null shows the room's newest. */
    val sessionId: String?,
    val chain: Chain,
    initialUIState: IcebergCeremonyUIState,
    private val activeIdentityStateFlow: StateFlow<Identity?>,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: IcebergCeremonyUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** The threshold a new ceremony would be opened at; starts at the smallest the group allows. */
    val threshold: MutableState<Int?> = mutableStateOf(null)

    private var watching: Job? = null

    fun initiate() {
        watching?.cancel()
        watching = viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                uiState = IcebergCeremonyUIState.Error("Couldn't find the group")
                return@launch
            }
            val thresholds = lightningRepository.ceremonyThresholds(chatRoomId)
            if (threshold.value == null) threshold.value = thresholds.firstOrNull()
            val hasOnchainWallet = lightningRepository.hasOnchainWallet(chatRoomId, chain)
            val holderCount = lightningRepository.keyHolderCount(chatRoomId)

            val sessions = if (sessionId != null) {
                lightningRepository.observeCeremony(sessionId)
            } else {
                lightningRepository.observeCeremonies(chatRoomId).map { it.firstOrNull() }
            }
            sessions.collect { session ->
                val messages = session?.let { lightningRepository.ceremonyMessages(it.id) }.orEmpty()
                uiState = IcebergCeremonyUIState.Loaded(
                    localChatRoom = localChatRoom,
                    session = session,
                    progress = session?.participants().orEmpty().associateWith { p -> messages.count { it.participantPublicKey == p } },
                    pending = session?.let { lightningRepository.pendingApproval(it) },
                    thresholds = thresholds,
                    holderCount = holderCount,
                    hasOnchainWallet = hasOnchainWallet,
                )
            }
        }
    }

    /** Whether this device can open a ceremony: none running, a deployable size, an onchain wallet, a key to sign with. */
    fun canPropose(): Boolean {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return false
        val running = loaded.session?.stage?.let { it != IcebergRitualStage.FAILED && it != IcebergRitualStage.COMPLETE } == true
        return !running && loaded.thresholds.isNotEmpty() && loaded.hasOnchainWallet && GroupLightningRollout.allows(chain) &&
            activeIdentityStateFlow.value?.nostrPrivateKey != null
    }

    fun propose(onFailure: () -> Unit) {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return
        val t = threshold.value ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val proposed = runCatching { lightningRepository.proposeCeremony(loaded.localChatRoom, activeUserPublicKey, privateKey, t, chain) }
                .onFailure { logger.e("Could not open a Lightning wallet key ceremony in $chatRoomId", it) }
                .getOrNull()
            isActionPending.value = false
            if (proposed == null) withContext(Dispatchers.Main) { onFailure() }
        }
    }

    fun approve(onFailure: () -> Unit) {
        val loaded = uiState as? IcebergCeremonyUIState.Loaded ?: return
        val session = loaded.session ?: return
        val step = loaded.pending ?: return
        val privateKey = activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray() ?: return onFailure()
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val done = runCatching { lightningRepository.approveCeremony(loaded.localChatRoom, session.id, step, privateKey) }
                .onFailure { logger.e("Could not approve $step of ${session.id}", it) }
                .isSuccess
            isActionPending.value = false
            if (!done) withContext(Dispatchers.Main) { onFailure() }
            // The ceremony may have moved without a new row being written; read it again.
            initiate()
        }
    }

    companion object {
        private const val TAG = "IcebergCeremonyViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            sessionId: String?,
            chain: Chain,
            activeIdentityStateFlow: StateFlow<Identity?>,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                IcebergCeremonyViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    sessionId = sessionId,
                    chain = chain,
                    initialUIState = IcebergCeremonyUIState.Loading,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    chatRepository = chatRepository,
                    lightningRepository = lightningRepository,
                )
            }
        }
    }
}
