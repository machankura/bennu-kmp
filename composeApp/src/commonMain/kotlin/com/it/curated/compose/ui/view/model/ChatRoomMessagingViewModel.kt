package com.it.curated.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.it.curated.compose.database.GENESIS_AT
import com.it.curated.compose.database.model.NegentropySynchronizeRequest
import com.it.curated.compose.database.model.types.SynchronizationFilter
import com.it.curated.compose.nostr.Relays
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.NostrRepository
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.view.state.ChatRoomMessagingUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

class ChatRoomMessagingViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialChatRoomMessagingUIState: ChatRoomMessagingUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository
): ViewModel() {

    var chatRoomMessagingUIState: ChatRoomMessagingUIState by mutableStateOf(initialChatRoomMessagingUIState)
        private set



    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateChatRoomDetail() {
        logger.d("compressed (most likely chat room): $chatRoomId")
        // Get ChatRoomById
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            chatRoomMessagingUIState = if (localChatRoom == null) {
                // Initiate new chat... and navigate to new chat...
                ChatRoomMessagingUIState.InitiatingNewChat
            } else {
                ChatRoomMessagingUIState.Loaded(
                    localChatRoom = localChatRoom
                )
            }
        }
    }

    fun initiateNewChat(
        onNavigateToChat: (Route) -> Unit
    ) {
        logger.d("initiateNewChat: $chatRoomId")
        // Get ChatRoomById
        viewModelScope.launch(Dispatchers.IO) {

            // Check if we have a chat with activeUserPublicKey and chatRoomId (pubkey) as participants
            val participants = chatRepository.getAllParticipantsWithPubKey(chatRoomId)

            participants.forEach { participant ->
                chatRepository.getChatRoomByIdentifier(participant.chatRoomId)?.let { localChatRoom ->
                    // TODO: Check that chatRoom is not a group chat...
                    if (localChatRoom.localParticipants.size == 1) {
                        // This is a chat with self...
                        // TODO: navigateToTheChat...
                        viewModelScope.launch(Dispatchers.Main) {
                             onNavigateToChat.invoke(
                                ChatRoomMessagingRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = localChatRoom.chatRoom.id,
                                    relayHint = relayHint
                                )
                            )
                        }
                        return@launch

                    } else if (localChatRoom.localParticipants.size == 2) {
                        val activeUserParticipant = localChatRoom.localParticipants.find { localParticipant -> localParticipant.participant.participantPublicKey == activeUserPublicKey }

                        if (activeUserParticipant != null) {
                            // We have found the chat we are looking for...
                            viewModelScope.launch(Dispatchers.Main) {
                                onNavigateToChat.invoke(
                                    ChatRoomMessagingRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = localChatRoom.chatRoom.id,
                                        relayHint = relayHint
                                    )
                                )
                            }
                            return@launch
                        }
                    }
                }
            }

            // Observe BOTH halves of what we need. Watching only the profile meant a key
            // package arriving later never re-triggered the check (indexing a kind-30443
            // touches MarmotKeyPackage, not Profile), so a lost race latched permanently.
            var creationStarted = false

            // Keep waiting (the collect below stays live, so a late arrival still works) but
            // stop looking like nothing is happening if the peer's key package never shows up.
            val lookupTimeout = viewModelScope.launch {
                delay(RELAY_LOOKUP_TIMEOUT)
                chatRoomMessagingUIState = ChatRoomMessagingUIState.Error(
                    "Couldn't find this profile's chat details on the relays yet. They may not have finished setting up Curated."
                )
            }

            combine(
                nostrRepository.observeProfileWithPublicKey(chatRoomId),
                chatRepository.observeMarmotKeyPackageForPublicKey(chatRoomId)
            ) { profile, keyPackage -> profile to keyPackage }
                .distinctUntilChanged()
                .collect { (profile, keyPackageEvent) ->
                    val profileIsResolved = profile != null && profile.createdAt > GENESIS_AT

                    if (!profileIsResolved || keyPackageEvent == null) {
                        // Whatever is missing, ask the relays for it. This used to be queued
                        // only when the Profile row was entirely absent, so the far more common
                        // "we have a placeholder / we have the profile but not the key package"
                        // cases sat there showing an error while nothing was being fetched.
                        scheduleProfileAndKeyPackageSync()
                        return@collect
                    }

                    lookupTimeout.cancel()
                    if (creationStarted) return@collect
                    creationStarted = true

                    logger.d("Create chat with: $profile")
                    try {
                        val newChatRoomId = chatRepository.createMlsDirectMessage(
                            userPublicKey = activeUserPublicKey,
                            peerPublicKey = chatRoomId,
                            peerKeyPackage = keyPackageEvent,
                        )

                        viewModelScope.launch(Dispatchers.Main) {
                            onNavigateToChat.invoke(
                                ChatRoomMessagingRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = newChatRoomId,
                                    relayHint = relayHint
                                )
                            )
                        }
                    } catch (e: Throwable) {
                        creationStarted = false
                        logger.e("Failed to create chat", e)
                        chatRoomMessagingUIState = ChatRoomMessagingUIState.Error("Failed to create chat")
                    }
                    return@collect
                }
        }
    }

    /**
     * Ask the DM relays for everything needed to start a chat with [chatRoomId] (a pubkey here):
     * the peer's metadata, their MLS key package and their DM relay list.
     */
    private suspend fun scheduleProfileAndKeyPackageSync() {
        val synchronizationFilter = SynchronizationFilter(
            kinds = arrayOf(
                MetadataEvent.KIND,
                KeyPackageEvent.KIND,
                ChatMessageRelayListEvent.KIND
            ),
            authors = arrayOf(
                chatRoomId
            )
            // No `limit`: it is ignored on the negentropy path but honoured on the REQ
            // fallback, where `limit = 1` returned a single newest event across all three
            // kinds — almost always the kind-0 — so the key package never arrived.
        )
        nostrRepository.queueNegentropySynchronizeRequest(
            Relays.DefaultDMRelayList.map { relay ->
                NegentropySynchronizeRequest(
                    id = NegentropySynchronizeRequest.computeId(
                        relay.url,
                        synchronizationFilter = synchronizationFilter
                    ),
                    purpose = "initiate-chat",
                    synchronizationFilter = synchronizationFilter,
                    relayURL = relay.url,
                    level = 0
                )
            }
        )
    }


    companion object {
        private const val TAG = "ChatRoomMessagingViewModel"

        /** How long to wait on the relays before telling the user we came up empty. */
        private val RELAY_LOOKUP_TIMEOUT = 20.seconds

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialChatRoomMessagingUIState: ChatRoomMessagingUIState = ChatRoomMessagingUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatRoomMessagingViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialChatRoomMessagingUIState = initialChatRoomMessagingUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository
                )
            }
        }
    }
}