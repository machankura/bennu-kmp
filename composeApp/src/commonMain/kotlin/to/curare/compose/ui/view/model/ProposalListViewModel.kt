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
import to.curare.compose.ui.view.state.ProposalFilter
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
    initialFilter: ProposalFilter = ProposalFilter.ALL,
): ViewModel() {

    var proposalListUIState: ProposalListUIState by mutableStateOf(initialProposalListUIState)
        private set

    /**
     * Which proposals the reader asked for, kept here rather than only on the
     * state it is drawn from.
     *
     * This is the load-bearing part. [initiate] rebuilds a whole
     * [ProposalListUIState.Loaded] on every emission, and the flow emits
     * whenever any member's message advances any session in the room -- which is
     * precisely when somebody is sitting on this screen watching. A `Loaded`
     * built with the field's default would snap the chips back to
     * [ProposalFilter.ALL] mid-scroll, and would do it most often in the busiest
     * rooms. So the selection outlives the state it appears on, and every
     * emission reads it.
     */
    private var filter: ProposalFilter = initialFilter

    private val logger = Logger.withTag(TAG)

    init {
        // A `Loaded` handed straight in -- a preview, a screen test -- carries a
        // filter of its own, and [initialFilter] is the caller's. The caller's
        // wins, so no screen can open with its chips saying one thing and its
        // rows showing another.
        select(initialFilter)
    }

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
                    proposals = sessions.map { asProposal(it) },
                    filter = filter
                )
            }
        }
    }

    /**
     * Shows only the proposals in one state, or all of them again.
     *
     * Applied to the state in hand as well as remembered for the next emission,
     * so the chips answer the tap rather than waiting for a session somewhere in
     * the room to move. A tap arriving while the screen is still loading is not
     * lost either -- it is remembered, and [initiate]'s first emission is
     * already filtered, which is what a deep link into a filter relies on.
     */
    fun select(filter: ProposalFilter) {
        this.filter = filter

        val current = proposalListUIState
        if (current is ProposalListUIState.Loaded) {
            proposalListUIState = current.copy(filter = filter)
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
            initialFilter: ProposalFilter = ProposalFilter.ALL,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProposalListViewModel(
                    chatRoomId = chatRoomId,
                    initialProposalListUIState = initialProposalListUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                    initialFilter = initialFilter
                )
            }
        }
    }
}
