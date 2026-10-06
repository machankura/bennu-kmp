package to.curare.compose.ui.view.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.Participant
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalParticipant
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.managers.FrostSigningManager

/**
 * When the signing screen offers to ask the quiet members again, and who it
 * counts as quiet.
 *
 * Both are derived on the state rather than collected, because everything they
 * are made of is already there -- the share holders, who offered a nonce, who
 * signed, who refused. A second flow for either would be a second thing that
 * can disagree with the rows drawn right above the button.
 *
 * The limits are what this pins. The offer is the coordinator's and nobody
 * else's: `acceptProposal` takes a session's coordinator from the author of the
 * proposal that opened it, so a resend from anybody else would have a member who
 * was missing the session believe the resender coordinates -- and then drop the
 * real coordinator's signer set.
 */
class FrostSigningResendStateTest {
    private val roomId = "11".repeat(32)

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private val holders = setOf(alice, bob, carol)

    private fun room(owner: String) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = "#Ekklesia",
            description = null,
            mlsGroupState = null,
        ),
        localParticipants = holders.sorted().map { publicKey ->
            LocalParticipant(
                participant = Participant(
                    participantPublicKey = publicKey,
                    chatRoomId = roomId,
                    relayHint = null,
                ),
                profile = null,
            )
        },
    )

    private fun session(
        owner: String = alice,
        coordinator: String = alice,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        resentAt: kotlin.time.Instant? = null,
    ) = FrostSigningSession(
        id = "s".repeat(64),
        chatRoomId = roomId,
        coordinatorPublicKey = coordinator,
        userPublicKey = owner,
        dkgSessionId = "ceremony".padEnd(64, '0'),
        threshold = 2,
        participantCount = 3,
        signerId = 0,
        resentAt = resentAt,
        stage = stage,
    )

    private val item = FrostSigningItem(
        sessionId = "s".repeat(64),
        itemIndex = 0,
        unsignedEventJson = "{}",
        eventId = "e".repeat(64),
        nonceRandom = "f".repeat(64),
    )

    private fun loaded(
        owner: String = alice,
        coordinator: String = alice,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        resentAt: kotlin.time.Instant? = null,
        offeredNonce: Set<String> = emptySet(),
        signed: Set<String> = emptySet(),
        declined: Set<String> = emptySet(),
        items: List<FrostSigningItem> = listOf(item),
    ) = FrostSigningUIState.Loaded(
        localChatRoom = room(owner),
        session = session(owner, coordinator, stage, resentAt),
        items = items,
        shareHolders = holders,
        offeredNonce = offeredNonce,
        signed = signed,
        declined = declined,
    )

    @Test
    fun `the quiet members are the share holders nobody has heard from`() {
        val state = loaded(offeredNonce = setOf(bob))

        // Alice is this device: her own answer is not something to knock about.
        assertEquals(setOf(carol), state.silent)
    }

    @Test
    fun `a member who refused is not quiet`() {
        // They answered. Asking again would be asking a question they closed.
        assertEquals(setOf(carol), loaded(declined = setOf(bob)).silent)
    }

    @Test
    fun `a member who signed their part is not quiet`() {
        assertEquals(setOf(carol), loaded(signed = setOf(bob)).silent)
    }

    @Test
    fun `a session nobody has answered is quiet in everybody but its proposer`() {
        assertEquals(setOf(bob, carol), loaded().silent)
    }

    @Test
    fun `the coordinator is offered the resend`() {
        assertTrue(loaded().canResend)
    }

    @Test
    fun `a member who did not propose is not offered it`() {
        // Correctness rather than etiquette -- see the class note.
        assertFalse(loaded(owner = bob, coordinator = alice).canResend)
    }

    @Test
    fun `a session everybody has answered has nothing to resend`() {
        val state = loaded(offeredNonce = setOf(bob), declined = setOf(carol))

        assertTrue(state.silent.isEmpty())
        assertFalse(state.canResend)
    }

    @Test
    fun `an abandoned session is not resent`() {
        // Its items' nonce seeds have been published against an aggregate
        // already. The retry is a fresh proposal, never this one again.
        assertFalse(loaded(stage = FrostSigningStage.FAILED).canResend)
    }

    @Test
    fun `a signed session can still be resent`() {
        // A member who never received the signature never applied the events.
        assertTrue(loaded(stage = FrostSigningStage.COMPLETE).canResend)
    }

    @Test
    fun `a session with no items yet is not resent`() {
        assertFalse(loaded(items = emptyList()).canResend)
    }

    @Test
    fun `a session not yet collected offers nothing`() {
        val state = FrostSigningUIState.Loaded(localChatRoom = room(alice))

        assertFalse(state.canResend)
        assertNull(state.resendAvailableAt)
    }

    @Test
    fun `a session never resent may be resent now`() {
        assertNull(loaded().resendAvailableAt)
    }

    @Test
    fun `a session just resent says when it may go again`() {
        val now = Clock.System.now()
        val state = loaded(resentAt = now)

        // Read by the screen to disable the button rather than hide the section:
        // a control that vanishes after a tap reads as one that failed.
        val availableAt = state.resendAvailableAt
        assertNotNull(availableAt)
        assertEquals(now + FrostSigningManager.RESEND_COOLDOWN, availableAt)
        assertTrue(state.canResend, "the offer stands; it is the button that goes quiet")
    }

    @Test
    fun `a session resent long ago may be resent again`() {
        val state = loaded(
            resentAt = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN * 2
        )

        assertNull(state.resendAvailableAt)
    }
}
