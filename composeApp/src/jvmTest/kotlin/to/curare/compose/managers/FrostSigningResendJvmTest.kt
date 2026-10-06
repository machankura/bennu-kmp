package to.curare.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.builder.getRoomDatabase
import to.curare.compose.database.model.ChatMessage
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.DkgParticipantMessage
import to.curare.compose.database.model.DkgSession
import to.curare.compose.database.model.FrostSignerMessage
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.GiftWrapPayload
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Participant
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalParticipant
import to.curare.compose.database.model.types.DkgRitualStage
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.extensions.toHex
import to.curare.compose.nostr.dkg.DkgRitualEvents
import to.curare.compose.nostr.frost.FrostSigningEvents

/**
 * What a resend does, against a real database and the real manager.
 *
 * A session can be left waiting forever on a member who would have agreed,
 * because the message asking them never arrived -- MLS moved its ratchet past
 * the generation it came in at (`docs/mls-skipped-keys.md`), or a
 * disappearing-message group expired it off the relay. Neither heals on its own
 * and neither is visible to the sender.
 *
 * Two halves, and the second is the one worth testing hardest. The coordinator
 * repeats what the coordinator wrote; a member named as silent repeats what
 * *they* wrote, because a relayed copy would be filed under the relayer. The
 * tests below are mostly about the limits of that: the same bytes and not new
 * ones, nothing at all from a device whose owner has not decided, and nothing
 * from a nudge that did not come from the coordinator.
 */
class FrostSigningResendJvmTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony; the test is about resending, not the DKG. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()

    /** The admin room: the group's key walked to the admin path, which is its id. */
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    private val alice = members[0]
    private val bob = members[1]
    private val carol = members[2]

    private lateinit var db: CurareDatabase
    private lateinit var room: LocalChatRoom

    @AfterTest
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
    }

    /**
     * One member's device: a room it can sign in, and their share to sign with.
     *
     * The room carries its roster, unlike the decline test's, because this one
     * is about who a message is addressed to: the room here is NIP-17 -- no MLS
     * state -- so a payload is sealed per p-tag, and narrowing a resend to the
     * quiet members is visible only if there is somebody to leave out.
     */
    private suspend fun openDevice(owner: HexKey = alice) {
        db = getRoomDatabase(Room.inMemoryDatabaseBuilder<CurareDatabase>())

        val index = members.indexOf(owner)

        val nostrEventId = "e$owner".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = owner,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128)
            )
        )
        db.profileDao().upsert(Profile(publicKey = owner, nostrEventId = nostrEventId))

        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = "#Ekklesia",
            description = SharedKeyDerivation.describe("Admins of the group."),
            mlsGroupState = null
        )
        db.chatRoomDao().upsert(chatRoom)

        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = alice,
                userPublicKey = owner,
                threshold = threshold,
                participantCount = participants,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[index].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() }
            )
        )

        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(
                    sessionId = ceremonyId,
                    participantPublicKey = member,
                    kind = DkgRitualEvents.HOST_KEY,
                    payload = hostKeys[i]
                )
            )
        }

        room = LocalChatRoom(
            chatRoom = chatRoom,
            localParticipants = members.map { member ->
                LocalParticipant(
                    Participant(
                        participantPublicKey = member,
                        chatRoomId = roomId,
                        relayHint = null
                    ),
                    null
                )
            }
        )
    }

    /** Alice proposes, which makes her device the coordinator and agrees on her behalf. */
    private suspend fun propose(): FrostSigningSession = FrostSigningManager.proposeSigning(
        database = db,
        localChatRoom = room,
        userPublicKey = alice,
        kind = 1,
        tags = arrayOf(),
        content = "Something for the group to say"
    )

    /**
     * A session as it sits on a member's device once Alice's proposal has
     * arrived: recorded, awaiting the owner's answer, and nothing published.
     *
     * The item carries a real unsigned event rather than `{}`, because a resend
     * refuses to repeat a proposal whose stored events will not parse -- a
     * proposal that quietly lost one would have every signer's contribution
     * rejected for being the wrong length.
     */
    private suspend fun proposalArrivedAt(owner: HexKey): FrostSigningSession {
        val session = FrostSigningSession(
            id = "s".repeat(64),
            chatRoomId = roomId,
            coordinatorPublicKey = alice,
            userPublicKey = owner,
            dkgSessionId = ceremonyId,
            threshold = threshold,
            participantCount = participants,
            signerId = members.indexOf(owner),
            derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH)
        )
        db.frostSigningSessionDao().upsert(session)
        db.frostSigningSessionDao().upsertItems(
            listOf(
                FrostSigningItem(
                    sessionId = session.id,
                    itemIndex = 0,
                    unsignedEventJson = unsignedEvent().toJson(),
                    eventId = unsignedEvent().id,
                    nonceRandom = "f".repeat(64)
                )
            )
        )
        return session
    }

    /** The one event these sessions sign, as the room's own key would author it. */
    private fun unsignedEvent(): Event = Event(
        id = "e".repeat(64),
        pubKey = roomId,
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = arrayOf(),
        content = "Something for the group to say",
        sig = ""
    )

    /**
     * One signing message, as the inbound path would hand it over.
     *
     * [silent] is what a resent proposal carries: the members the coordinator
     * says it has heard nothing from.
     */
    private suspend fun receive(
        sessionId: String,
        kind: Kind,
        member: HexKey,
        content: String = "",
        silent: List<HexKey>? = null,
        includeKey: Boolean = false
    ) {
        FrostSigningManager.processSigningPayload(
            database = db,
            localChatRoom = room,
            innerEvent = Event(
                id = "$kind$member$content$silent".hashCode().toUInt().toString(16).padStart(64, '0'),
                pubKey = member,
                createdAt = Clock.System.now().epochSeconds,
                kind = kind,
                tags = FrostSigningEvents.assembleTags(
                    sessionId = sessionId,
                    dkgSessionId = if (includeKey) ceremonyId else null,
                    silentMembers = silent
                ),
                content = content,
                sig = ""
            ),
            userPublicKey = room.chatRoom.userPublicKey
        )
    }

    private suspend fun resend(sessionId: String) =
        FrostSigningManager.resend(database = db, localChatRoom = room, sessionId = sessionId)

    private suspend fun session(id: String) = db.frostSigningSessionDao().getSessionById(id)!!

    private suspend fun transcript(type: String) = db.chatMessageDao()
        .getChatMessagesByChatRoomId(roomId)
        .map { it.chatMessage }
        .filter { it.messageType == type }

    /** What this device has queued for the group, of one kind. The room is NIP-17 here. */
    private suspend fun queued(kind: Kind) = db.giftWrapPayloadDao()
        .getByChatRoomAndKinds(roomId, listOf(kind))
        .filter { it.publicKey == room.chatRoom.userPublicKey }

    private fun pTags(tags: Array<Array<String>>): Set<HexKey> =
        tags.filter { it[0] == "p" }.map { it[1] }.toSet()

    /**
     * The row [publish] added to the queue, found by id rather than by time.
     *
     * `createdAt` is stored to the second, so a resend and the publish it
     * repeats routinely share one -- picking "the newest" is a coin toss
     * between them, and the coin came up wrong often enough to be worth not
     * tossing.
     */
    private suspend fun queuedBy(kind: Kind, publish: suspend () -> Unit): GiftWrapPayload {
        val before = queued(kind).map { it.id }.toSet()
        publish()
        val after = queued(kind).filter { it.id !in before }
        assertEquals(1, after.size, "expected exactly one new queued row of kind $kind")
        return after.single()
    }

    // ---- the coordinator's half -------------------------------------------

    @Test
    fun `a resend repeats the proposal and names who has not answered`() = runBlocking {
        openDevice()
        val session = propose()

        // Alice proposed, so her nonce is on file. Nobody else has said anything.
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        assertEquals(
            2,
            queued(FrostSigningEvents.PROPOSAL).size,
            "the resend is a second proposal, not an edit of the first"
        )

        assertEquals(
            setOf(bob, carol),
            FrostSigningEvents.parseSilentMembers(resent.tags)?.toSet(),
            "both members who have said nothing are named"
        )

        // The proposer is never in the silent set: it is their own session.
        assertFalse(alice in FrostSigningEvents.parseSilentMembers(resent.tags)!!)
    }

    @Test
    fun `a resend carries the key, so a device with no session can open one`() = runBlocking {
        openDevice()
        val session = propose()

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        // Without this a receiver that never saw the first proposal drops the
        // second: `acceptProposal` refuses a proposal that names no key.
        assertEquals(ceremonyId, FrostSigningEvents.parseKey(resent.tags))
    }

    @Test
    fun `a resend repeats the same events, not new ones`() = runBlocking {
        openDevice()
        val session = propose()
        val signing = db.frostSigningSessionDao().getItems(session.id).map { it.eventId }

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }
        val proposed = FrostSigningEvents.decodeProposal(resent.content)

        // The load-bearing one. A resend that proposed different events under the
        // same session id would be asking for two signatures out of one secret
        // nonce, which `acceptProposal` refuses -- so it would be a resend that
        // repairs nothing while looking like it worked.
        assertEquals(signing, proposed?.map { it.id })
        assertEquals(signing, db.frostSigningSessionDao().getItems(session.id).map { it.eventId })
    }

    @Test
    fun `a resend is narrowed to the members it is for`() = runBlocking {
        openDevice()
        val session = propose()

        val first = queued(FrostSigningEvents.PROPOSAL).single()

        // Bob answers; Carol does not. A refusal rather than a nonce, because
        // Alice already holds her own: a second nonce reaches the threshold and
        // sends her off to aggregate, which this test has nothing valid to give
        // her. One refusal in a 2-of-3 group leaves the session open.
        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")
        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        // A NIP-17 payload is sealed once per p-tag, so repeating a message for
        // the one member who missed it should cost one wrap and not the room's
        // worth. The first proposal named everybody; this one names Carol.
        assertEquals(setOf(carol), pTags(resent.tags))
        assertEquals(setOf(bob, carol), pTags(first.tags))
    }

    @Test
    fun `a member who declined is not silent`() = runBlocking {
        openDevice()
        val session = propose()

        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")

        val resent = queuedBy(FrostSigningEvents.PROPOSAL) { assertTrue(resend(session.id)) }

        // Bob answered. Knocking again would be asking a question he has closed.
        assertEquals(setOf(carol), FrostSigningEvents.parseSilentMembers(resent.tags)?.toSet())
    }

    @Test
    fun `a resend with nobody silent does nothing`() = runBlocking {
        openDevice()
        val session = propose()

        // Bob refuses, and Carol's nonce is written straight to the message table
        // rather than delivered: a second nonce reaching Alice would send her off
        // to aggregate it, and this test has no valid nonce to give her. What is
        // being checked is who counts as silent, which is read from these rows.
        receive(session.id, FrostSigningEvents.DECLINE, bob, "Declined to sign")
        db.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = session.id,
                signerPublicKey = carol,
                kind = FrostSigningEvents.NONCE,
                payload = "c".repeat(132)
            )
        )

        assertFalse(resend(session.id))
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
        assertTrue(transcript(ChatMessage.TYPE_FROST_RESENT).isEmpty())
        assertNull(session(session.id).resentAt)
    }

    @Test
    fun `only the device that proposed may resend`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        assertFalse(resend(session.id))
        assertTrue(queued(FrostSigningEvents.PROPOSAL).isEmpty())
    }

    @Test
    fun `an abandoned session is not resent`() = runBlocking {
        openDevice()
        val session = propose()

        receive(session.id, FrostSigningEvents.FAILURE, bob, "Something went wrong")
        assertEquals(FrostSigningStage.FAILED, session(session.id).stage)

        // Its items' nonce seeds have been published against an aggregate
        // already. The retry is a fresh proposal, never this one again.
        assertFalse(resend(session.id))
        assertEquals(1, queued(FrostSigningEvents.PROPOSAL).size)
    }

    @Test
    fun `the cooldown stops a second resend`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))
        val resentAt = session(session.id).resentAt
        assertNotNull(resentAt)

        assertFalse(resend(session.id), "a second tap inside the cooldown publishes nothing")
        assertEquals(2, queued(FrostSigningEvents.PROPOSAL).size)
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_RESENT).size)
        assertEquals(resentAt, session(session.id).resentAt)
    }

    @Test
    fun `a resend past the cooldown goes out again`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))

        // Wound back rather than waited out: the cooldown is minutes.
        db.frostSigningSessionDao().upsert(
            session(session.id).copy(
                resentAt = Clock.System.now() - FrostSigningManager.RESEND_COOLDOWN * 2
            )
        )

        assertTrue(resend(session.id))
        assertEquals(3, queued(FrostSigningEvents.PROPOSAL).size)
        assertEquals(2, transcript(ChatMessage.TYPE_FROST_RESENT).size)
    }

    @Test
    fun `two resends in the same second do not collide`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))
        db.frostSigningSessionDao().upsert(session(session.id).copy(resentAt = null))
        assertTrue(resend(session.id))

        val proposals = queued(FrostSigningEvents.PROPOSAL)

        // An outbound row's id is a hash over its `created_at`, which is stored
        // to the second. Two identical publishes inside one second would hash
        // the same, and the upsert would put the already-sent row back on the
        // queue with its mapping cleared -- double-sending it and losing the
        // group event it became. So the second steps a second forward instead.
        assertEquals(3, proposals.size)
        assertEquals(3, proposals.map { it.id }.toSet().size, "three rows, three ids")
    }

    @Test
    fun `a resend writes one line in the transcript`() = runBlocking {
        openDevice()
        val session = propose()

        assertTrue(resend(session.id))

        val lines = transcript(ChatMessage.TYPE_FROST_RESENT)
        assertEquals(1, lines.size)
        assertEquals(alice, lines.single().senderPublicKey)
        assertTrue(lines.single().content.contains("2 members"))
    }

    // ---- the member's half ------------------------------------------------

    @Test
    fun `a member named as silent republishes the nonce they already sent`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)

        val first = queued(FrostSigningEvents.NONCE)
        assertEquals(1, first.size)

        // Alice never got it, and says so.
        val resent = queuedBy(FrostSigningEvents.NONCE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        assertEquals(2, queued(FrostSigningEvents.NONCE).size, "the nonce goes out a second time")

        // The load-bearing assertion in this file. Two different nonces for one
        // message against two aggregates is how a secret share is extracted, so
        // a republish has to be the same bytes and not a fresh nonce.
        assertEquals(first.single().content, resent.content, "the same bytes, not a new nonce")
    }

    @Test
    fun `a republished nonce goes only to the coordinator`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)

        val resent = queuedBy(FrostSigningEvents.NONCE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        // A nonce is read by nobody but the coordinator -- `orderedNonces` is
        // called under `isCoordinator` -- so repeating it for the room would be
        // two wraps spent telling people who do not look.
        assertEquals(setOf(alice), pTags(resent.tags))
    }

    @Test
    fun `a member named as silent who has not decided publishes nothing`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())

        receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))

        // The strongest limit here. A message from the coordinator must never be
        // able to put this device's share behind events its owner has not agreed
        // to; the session is still waiting on a person, and says so.
        assertTrue(queued(FrostSigningEvents.NONCE).isEmpty())
        assertNull(session(session.id).signApprovedAt)
        assertTrue(
            FrostSigningManager.isAwaitingApproval(
                session(session.id),
                db.frostSigningSessionDao().getItems(session.id)
            )
        )
    }

    @Test
    fun `a member named as silent republishes the refusal they already sent`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.decline(database = db, localChatRoom = room, sessionId = session.id)
        val first = queued(FrostSigningEvents.DECLINE)
        assertEquals(1, first.size)

        val resent = queuedBy(FrostSigningEvents.DECLINE) {
            receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))
        }

        assertEquals(2, queued(FrostSigningEvents.DECLINE).size)
        assertEquals(first.single().content, resent.content)
    }

    @Test
    fun `a member not named stays quiet`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        assertEquals(1, queued(FrostSigningEvents.NONCE).size)

        receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(carol))

        assertEquals(1, queued(FrostSigningEvents.NONCE).size)
    }

    @Test
    fun `a nudge from somebody who is not the coordinator is ignored`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(database = db, localChatRoom = room, sessionId = session.id)
        assertEquals(1, queued(FrostSigningEvents.NONCE).size)

        // Carol naming Bob as silent. The bytes Bob would send are ones he has
        // already sent, so nothing on the wire changes -- but a publish anybody
        // in the room can trigger is not a thing to leave lying around.
        receive(session.id, FrostSigningEvents.PROPOSAL, carol, proposalContent(), silent = listOf(bob))

        assertEquals(1, queued(FrostSigningEvents.NONCE).size)
    }

    @Test
    fun `a resent proposal for a session already held changes nothing about it`() = runBlocking {
        openDevice(bob)
        val session = proposalArrivedAt(bob)
        val before = db.frostSigningSessionDao().getItems(session.id)

        receive(session.id, FrostSigningEvents.PROPOSAL, alice, proposalContent(), silent = listOf(bob))

        val after = db.frostSigningSessionDao().getItems(session.id)
        assertEquals(before.map { it.eventId }, after.map { it.eventId })
        assertEquals(before.map { it.nonceRandom }, after.map { it.nonceRandom })
        // `acceptProposal` returns the session it already holds before it
        // announces anything, so a resend writes no second "asked the group"
        // line -- and this device's session was written straight to the row,
        // so there is no first one either.
        assertTrue(transcript(ChatMessage.TYPE_FROST_STARTED).isEmpty())
    }

    /** What a resent proposal carries: the events of [proposalArrivedAt]'s session. */
    private fun proposalContent(): String =
        FrostSigningEvents.encodeProposal(listOf(unsignedEvent()))
}
