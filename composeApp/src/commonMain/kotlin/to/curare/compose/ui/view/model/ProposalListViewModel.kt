package to.curare.compose.ui.view.model

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import to.curare.compose.database.model.intermdiate.LocalFrostSigningSession
import to.curare.compose.extensions.shortened
import to.curare.compose.managers.FrostSigningManager
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.text.ProposedEvent
import to.curare.compose.ui.view.state.ProposalListUIState

/**
 * Everything a group has asked its shared key to sign.
 *
 * A room signs repeatedly, and can have two sessions open at once -- proposing a
 * chapter and the translation that depends on it is two. Watching the whole list
 * rather than the latest session is the point: which of them is waiting on the
 * reader is a fact about each one, and there is no ordering of them in which the
 * second is not worth showing.
 */
class ProposalListViewModel(
    val chatRoomId: String,
    initialProposalListUIState: ProposalListUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var proposalListUIState: ProposalListUIState by mutableStateOf(initialProposalListUIState)
        private set

    private val logger = Logger.withTag(TAG)

    /**
     * Loads the room, then watches its proposals for as long as the screen lives.
     *
     * Every one of them moves on messages arriving from other members, so a
     * screen that read the list once would go stale the moment somebody else
     * signed -- which is exactly when a member is likely to be looking at it.
     */
    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                proposalListUIState = ProposalListUIState.Error("Couldn't find the group")
                return@launch
            }

            frostSigningRepository.observeSessionsForChatRoom(chatRoomId).collect { sessions ->
                logger.d("${sessions.size} proposals in $chatRoomId")

                proposalListUIState = ProposalListUIState.Loaded(
                    localChatRoom = localChatRoom,
                    proposals = sessions.map { asProposal(it) }
                )
            }
        }
    }

    /**
     * One session as a row.
     *
     * Whether it is waiting on the reader is asked of the manager rather than
     * worked out here, so the list and the signing screen cannot disagree about
     * which proposals still have a decision in them.
     */
    private fun asProposal(local: LocalFrostSigningSession): ProposalListUIState.Proposal {
        val items = local.items
        val events = items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }

        return ProposalListUIState.Proposal(
            session = local.session,
            proposerName = when {
                local.session.isCoordinator() -> "You"
                else -> local.proposer?.humanReadableNameOrPubkey()
                    ?: local.session.coordinatorPublicKey.shortened()
            },
            signs = events.map { ProposedEvent.summarize(it) },
            unreadable = items.size - events.size,
            awaitsYou = FrostSigningManager.isAwaitingApproval(local.session, items)
        )
    }

    companion object {
        private const val TAG = "ProposalListViewModel"

        fun factory(
            chatRoomId: String,
            initialProposalListUIState: ProposalListUIState = ProposalListUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProposalListViewModel(
                    chatRoomId = chatRoomId,
                    initialProposalListUIState = initialProposalListUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
