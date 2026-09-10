package to.curare.compose.ui.view.model

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
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.nostr.frost.FrostSigningEvents
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.view.state.FrostSigningUIState

class FrostSigningViewModel(
    val chatRoomId: String,
    val sessionId: String,
    val activeUserPublicKey: HexKey,
    initialFrostSigningUIState: FrostSigningUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var frostSigningUIState: FrostSigningUIState by mutableStateOf(initialFrostSigningUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Loads the room, then watches the session for as long as the screen lives.
     *
     * A session moves on messages arriving from other members, so a screen that
     * read it once would sit still while the rest of the group signed around it.
     */
    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                frostSigningUIState = FrostSigningUIState.Error("Couldn't find the group")
                return@launch
            }

            frostSigningUIState = FrostSigningUIState.Loaded(localChatRoom = localChatRoom)

            combine(
                frostSigningRepository.observeSessionById(sessionId),
                frostSigningRepository.observeMessages(sessionId),
                frostSigningRepository.observeItems(sessionId)
            ) { session, messages, items -> Triple(session, messages, items) }
                .collect { (session, messages, items) ->
                    frostSigningUIState = FrostSigningUIState.Loaded(
                        localChatRoom = localChatRoom,
                        session = session,
                        items = items,
                        offeredNonce = messages
                            .filter { it.kind == FrostSigningEvents.NONCE }
                            .map { it.signerPublicKey }
                            .toSet(),
                        signed = messages
                            .filter { it.kind == FrostSigningEvents.PARTIAL_SIGNATURE }
                            .map { it.signerPublicKey }
                            .toSet()
                    )
                }
        }
    }

    /** The events the group is being asked to sign, for showing them before they agree. */
    fun proposedEvents(items: List<FrostSigningItem>): List<Event> =
        items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }

    fun signedEvents(items: List<FrostSigningItem>): List<Event> =
        frostSigningRepository.signedEvents(items)

    fun approve(onDone: () -> Unit) {
        val state = frostSigningUIState as? FrostSigningUIState.Loaded ?: return
        val sessionId = state.session?.id ?: return

        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            frostSigningRepository.approve(
                localChatRoom = state.localChatRoom,
                sessionId = sessionId
            )

            isActionPending.value = false

            viewModelScope.launch(Dispatchers.Main) { onDone.invoke() }
        }
    }

    fun decline(onDone: () -> Unit) {
        val state = frostSigningUIState as? FrostSigningUIState.Loaded ?: return
        val sessionId = state.session?.id ?: return

        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            frostSigningRepository.decline(
                localChatRoom = state.localChatRoom,
                sessionId = sessionId
            )

            isActionPending.value = false

            viewModelScope.launch(Dispatchers.Main) { onDone.invoke() }
        }
    }

    companion object {
        private const val TAG = "FrostSigningViewModel"

        fun factory(
            chatRoomId: String,
            sessionId: String,
            activeUserPublicKey: HexKey,
            initialFrostSigningUIState: FrostSigningUIState = FrostSigningUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FrostSigningViewModel(
                    chatRoomId = chatRoomId,
                    sessionId = sessionId,
                    activeUserPublicKey = activeUserPublicKey,
                    initialFrostSigningUIState = initialFrostSigningUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
