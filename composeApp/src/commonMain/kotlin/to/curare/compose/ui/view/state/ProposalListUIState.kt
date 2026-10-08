package to.curare.compose.ui.view.state

import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.text.ProposedEvent

sealed interface ProposalListUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,

        /** Every proposal the room has made, newest first. */
        val proposals: List<Proposal> = emptyList(),

        /**
         * Which of them the reader has asked to see.
         *
         * Held on the state and not only on the view model, because the chips
         * have to draw the current selection and the counts beside it in the
         * same pass that draws the rows it selected -- a selection the row list
         * disagreed with would be a chip marked for a filter that is not applied.
         */
        val filter: ProposalFilter = ProposalFilter.ALL,
    ): ProposalListUIState {
        /**
         * The ones that cannot move until this member answers them.
         *
         * Split out rather than sorted to the top, because they are a different
         * kind of thing to read: the rest of the list is what the group has done,
         * these are what it is waiting on the reader for. A group can have several
         * at once -- proposing a chapter and its translation is two -- and burying
         * the second one under the first one's history is the whole reason this
         * screen exists.
         */
        val waitingForYou: List<Proposal> = proposals.filter { it.state == ProposalState.NEEDS_YOU }

        val rest: List<Proposal> = proposals.filterNot { it.state == ProposalState.NEEDS_YOU }

        /**
         * The rows [filter] actually asks for.
         *
         * [proposals] is left whole beside it, because the counts on the chips
         * are counts of the room and not of the selection -- a chip that read
         * its own number off the filtered list would say 0 for every filter but
         * the chosen one.
         */
        val visible: List<Proposal> = when (val only = filter.state) {
            null -> proposals
            else -> proposals.filter { it.state == only }
        }

        /**
         * How many proposals are in each state, for the number on each chip.
         *
         * Every state is present, zero included: a room that has never
         * abandoned a proposal still gets an "abandoned" chip reading 0, because
         * a chip row that gains and loses chips as sessions advance moves under
         * the thumb of the person reading it -- and sessions advance while this
         * screen is open, which is the whole reason the screen exists.
         */
        val counts: Map<ProposalState, Int> = ProposalState.entries.associateWith { state ->
            proposals.count { it.state == state }
        }
    }

    /**
     * One proposal, as a row.
     *
     * Everything here is derived once per emission rather than at render: the
     * events are parsed out of stored JSON, which is not work to repeat on every
     * recomposition of a scrolling list.
     */
    data class Proposal(
        val session: FrostSigningSession,

        /** Whoever put it to the group, as a name to read. */
        val proposerName: String,

        /**
         * What it signs, in the order the batch fixed -- so the first is the one
         * the others hang off, and is what the row is named after.
         */
        val signs: List<ProposedEvent.Summary> = emptyList(),

        /**
         * How many of its events could not be read back.
         *
         * Shown rather than hidden. A batch is all-or-nothing, so an unreadable
         * item is a reason not to sign any of it, and a row that quietly listed
         * only the readable ones would be understating what is being asked.
         */
        val unreadable: Int = 0,

        /** Whether the session is still waiting on this device's owner. */
        val awaitsYou: Boolean = false,
    ) {
        /** The batch's size, readable or not. */
        val eventCount: Int get() = signs.size + unreadable

        /**
         * Where it has got to, in the terms the screen reads it in.
         *
         * Asked once here rather than at each of the four places that used to
         * work it out for themselves -- the section split, the row's icon, that
         * icon's description, and the status line. They were four `when` chains
         * over the same two facts, and the chips would have been a fifth; the
         * way a chip comes to count a proposal the row beneath it describes
         * differently is by asking the question again.
         *
         * [awaitsYou] is tried first and wins, because it is the one state that
         * is not a stage: an open session waiting on this member and an open
         * session waiting on everybody else are both `COLLECTING_NONCES`.
         *
         * The inner `when` is exhaustive on purpose, with no `else`. A stage
         * added to `FrostSigningStage` later should fail to compile here rather
         * than quietly land in whichever arm happened to be last, because what
         * the reader is waiting on is the one thing this file cannot guess.
         */
        val state: ProposalState get() = when {
            awaitsYou -> ProposalState.NEEDS_YOU

            else -> when (session.stage) {
                FrostSigningStage.COLLECTING_NONCES -> ProposalState.WAITING_ON_MEMBERS
                FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> ProposalState.BEING_SIGNED
                FrostSigningStage.COMPLETE -> ProposalState.SIGNED
                FrostSigningStage.FAILED -> ProposalState.ABANDONED
            }
        }
    }

    data class Error(
        val message: String
    ): ProposalListUIState

    data object Loading: ProposalListUIState
}
