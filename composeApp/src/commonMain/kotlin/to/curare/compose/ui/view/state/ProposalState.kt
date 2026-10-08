package to.curare.compose.ui.view.state

/**
 * Where a proposal has got to, as a reader scanning a list of them cares.
 *
 * Deliberately not [to.curare.compose.database.model.types.FrostSigningStage].
 * The stage is how far the protocol has run; this is what there is to do about
 * it, and the two part company on the only state anybody comes to this screen
 * looking for: a proposal waiting on *this* member is `COLLECTING_NONCES` like
 * every other open one, and what sets it apart is
 * `FrostSigningSession.signApprovedAt` and `signDeclinedAt` being null. So
 * [NEEDS_YOU] cannot be read off the stage at all, and a filter keyed on the
 * enum would be a filter missing its reason to exist.
 *
 * Exhaustive and ordered by how much it asks of the reader, which is the order
 * the chips are offered in.
 */
enum class ProposalState {
    /**
     * Waiting on this member to agree or refuse, and going nowhere until they do.
     *
     * The same question the signing screen gates its buttons on -- see
     * `ProposalListUIState.Proposal.awaitsYou`, which is
     * `FrostSigningManager.isAwaitingApproval` and not a second opinion of it.
     */
    NEEDS_YOU,

    /**
     * Open, and this member has already said their piece -- or was never needed.
     *
     * Covers more than it looks like: agreed and waiting on others, refused and
     * left the others to it, picked to sign, not picked and watching. All of them
     * are the same thing to a reader deciding where to look, which is "not here",
     * and the row's own status line says which one it is.
     */
    IN_PROGRESS,

    /** Aggregated and verified. The group signed it. */
    SIGNED,

    /** Abandoned. `FrostSigningSession.failureReason` says why, when it knows. */
    ABANDONED,
}
