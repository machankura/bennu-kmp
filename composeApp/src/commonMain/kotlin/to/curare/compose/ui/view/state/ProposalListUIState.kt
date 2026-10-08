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
         * than quietly land in [ProposalState.IN_PROGRESS], because whether it
         * is an ending is the one thing this file cannot guess.
         */
        val state: ProposalState get() = when {
            awaitsYou -> ProposalState.NEEDS_YOU

            else -> when (session.stage) {
                FrostSigningStage.COLLECTING_NONCES,
                FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES -> ProposalState.IN_PROGRESS

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
