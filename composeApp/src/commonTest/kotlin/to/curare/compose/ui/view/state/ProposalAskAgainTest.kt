package to.curare.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.managers.FrostSigningManager

/**
 * When a row offers to ask the quiet members again, and when it only looks like
 * it could.
 *
 * The offer is the coordinator's and nobody else's -- `acceptProposal` takes a
 * session's coordinator from the author of the proposal that opened it, so a
 * resend from anybody else would have a member who was missing the session
 * believe the resender coordinates, and then drop the real coordinator's signer
 * set. The row expresses that as a null rather than a zero: not ours to send is
 * a different fact from nobody left to send to, and only one of them could ever
 * become a button.
 */
class ProposalAskAgainTest {
    private val roomId = "11".repeat(32)
    private val me = "a".repeat(64)
    private val somebodyElse = "b".repeat(64)

    private fun session(
        coordinator: String = me,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
        resentAt: kotlin.time.Instant? = null,
    ) = FrostSigningSession(
        id = "session",
        chatRoomId = roomId,
        coordinatorPublicKey = coordinator,
        userPublicKey = me,
        dkgSessionId = "ceremony",
        threshold = 2,
        participantCount = 3,
        signerId = 1,
        stage = stage,
        resentAt = resentAt,
    )

    private fun proposal(
        session: FrostSigningSession = session(),
        owing: Int? = null,
    ) = ProposalListUIState.Proposal(
        session = session,
        proposerName = "You",
        owing = owing,
    )

    @Test
    fun `a session waiting on somebody offers the ask`() {
        assertTrue(proposal(owing = 1).canAskAgain)
        assertTrue(proposal(owing = 3).canAskAgain)
    }

    @Test
    fun `a session waiting on nobody offers nothing, because there is nobody to ask`() {
        assertFalse(proposal(owing = 0).canAskAgain)
    }

    /**
     * Null is what the view model answers for a session this device did not
     * propose, and it must not read as "waiting on nobody" turning into a button
     * by some later arithmetic.
     */
    @Test
    fun `a session that is not ours to send offers nothing`() {
        assertFalse(proposal(session(coordinator = somebodyElse), owing = null).canAskAgain)
        assertNull(proposal(session(coordinator = somebodyElse)).owing)
    }

    @Test
    fun `a proposal says nothing about the cooldown until one has been sent`() {
        assertNull(proposal(owing = 1).askAgainAt)
    }

    @Test
    fun `an ask sent just now is still cooling down`() {
        val justNow = proposal(session(resentAt = Clock.System.now()), owing = 1)

        assertNotNull(justNow.askAgainAt, "the cooldown should still be running")
        assertTrue(
            justNow.canAskAgain,
            "the offer stays and the button goes quiet; a control that vanishes reads as one that failed"
        )
    }

    @Test
    fun `an ask sent longer ago than the cooldown may go again`() {
        val old = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN - kotlin.time.Duration.parse("1s")

        assertNull(proposal(session(resentAt = old), owing = 1).askAgainAt)
    }

    /** The boundary belongs to the manager, so the row reads it rather than guessing. */
    @Test
    fun `the cooldown the row honours is the one the manager enforces`() {
        val sentAt = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN / 2
        val halfway = proposal(session(resentAt = sentAt), owing = 1)

        assertEquals(
            sentAt + FrostSigningManager.RESEND_COOLDOWN,
            halfway.askAgainAt,
            "the row's cooldown drifted from the manager's"
        )
    }

    /**
     * The offer does not depend on the stage here, because the view model has
     * already decided which stages it asks about -- see `owingFor`. A row only
     * ever receives a count for a session that could be resent.
     */
    @Test
    fun `a count on any stage the view model asks about becomes an offer`() {
        for (stage in listOf(
            FrostSigningStage.COLLECTING_NONCES,
            FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
        )) {
            assertTrue(
                proposal(session(stage = stage), owing = 1).canAskAgain,
                "$stage should be askable"
            )
        }
    }
}
