package to.curare.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.builder.getRoomDatabase
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.DkgParticipantMessage
import to.curare.compose.database.model.DkgSession
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.types.DkgRitualStage
import to.curare.compose.extensions.toHex
import to.curare.compose.nostr.dkg.DkgRitualEvents
import to.curare.compose.nostr.nip30303.ChapterEvent
import to.curare.compose.nostr.nip30303.ChunkEvent
import to.curare.compose.text.Markdown

/**
 * What a chapter proposal actually puts in front of the group.
 *
 * `SignedChapterTest` builds the batch by hand and signs it, which proves the
 * crypto and the rows but assumes the shape. This runs the real proposal
 * against a real database, because the shape is the part with the sharp edge:
 * a chunk carries its chapter's id, and that id does not exist until the
 * proposal authors the chapter under the group's key at the room's derivation
 * path. Both are resolved inside `proposeSigningBatch` and neither is knowable
 * to the caller, which is why the chunks are built from what it hands back.
 *
 * If that came apart, the chapter would still be signed and every chunk would
 * still verify -- against a chapter id nobody has. They would fail a foreign
 * key on the way in and the chapter would simply arrive empty.
 */
class ChapterBatchProposalJvmTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony; the test is about the proposal, not the DKG. */
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

    private val proposer = members.first()
    private val artifactVersionId = "b".repeat(64)

    private lateinit var db: CurareDatabase
    private lateinit var room: LocalChatRoom

    @AfterTest
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
    }

    /** The proposer's device: a room it can sign in, and the share to sign with. */
    private suspend fun openDevice() {
        db = getRoomDatabase(Room.inMemoryDatabaseBuilder<CurareDatabase>())

        // Profile hangs off a nostr event, and a room off a profile. Neither is
        // anything this test is about; they are the foreign keys in the way.
        val nostrEventId = "e$proposer".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = proposer,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128)
            )
        )
        db.profileDao().upsert(Profile(publicKey = proposer, nostrEventId = nostrEventId))

        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = proposer,
            subject = "#Ekklesia",
            description = SharedKeyDerivation.describe("Admins of the group."),
            mlsGroupState = null
        )
        db.chatRoomDao().upsert(chatRoom)

        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = proposer,
                userPublicKey = proposer,
                threshold = threshold,
                participantCount = participants,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[0],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[0].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() }
            )
        )

        members.forEachIndexed { index, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(
                    sessionId = ceremonyId,
                    participantPublicKey = member,
                    kind = DkgRitualEvents.HOST_KEY,
                    payload = hostKeys[index]
                )
            )
        }

        room = LocalChatRoom(chatRoom = chatRoom)
    }

    private fun chapterTemplate(originalText: String, name: String = "Chapter 1") =
        ChapterEvent.build(
            artifactVersionId = artifactVersionId,
            name = name,
            originalText = originalText,
            index = 0,
            wordCount = Markdown.wordCount(originalText),
            characterCount = Markdown.characterCount(originalText),
        )

    /** Exactly what `AddChapterViewModel.addChapter` proposes. */
    private suspend fun proposeChapter(originalText: String) =
        FrostSigningManager.proposeSigningBatch(
            database = db,
            localChatRoom = room,
            userPublicKey = proposer,
            lead = chapterTemplate(originalText),
            dependents = ChunkEvent::splitOf
        )

    private suspend fun itemEvents(sessionId: String): List<Event> =
        db.frostSigningSessionDao().getItems(sessionId).map { Event.fromJson(it.unsignedEventJson) }

    private fun paragraphs(count: Int) = (1..count).joinToString("\n\n") { "Paragraph $it." }

    @Test
    fun `the chapter leads the batch and its chunks follow`() = runBlocking {
        openDevice()

        val session = proposeChapter("The first paragraph.\n\nThe second paragraph.")
        val events = itemEvents(session.id)

        // Item order is apply order, and a chunk row whose chapter does not
        // exist yet is a foreign key violation. The chapter has to be first.
        assertEquals(3, events.size)
        assertEquals(ChapterEvent.KIND, events.first().kind)
        assertTrue(events.drop(1).all { it.kind == ChunkEvent.KIND })
    }

    @Test
    fun `every chunk names the chapter as the group will author it`() = runBlocking {
        openDevice()

        val session = proposeChapter("The first paragraph.\n\nThe second paragraph.")
        val events = itemEvents(session.id)
        val chapter = events.first()

        // The claim the whole `lead`/`dependents` form exists for. The id here is
        // a hash over the group's key at the room's path; a caller that built the
        // chunks before proposing could not have known it.
        val chunks = events.drop(1).map {
            ChunkEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
        }

        assertTrue(chunks.all { it.chapterId() == chapter.id })
        assertEquals(listOf("The first paragraph.", "The second paragraph."), chunks.map { it.content })
    }

    @Test
    fun `the whole batch is authored by the room, not by the proposer`() = runBlocking {
        openDevice()

        val events = itemEvents(proposeChapter("One.\n\nTwo.").id)

        // Signing runs at the path the room was derived at, so the author of
        // every item is the room's own id -- chunks included, which is what lets
        // one be checked without being told which key to expect.
        assertTrue(events.all { it.pubKey == roomId })
        assertTrue(events.none { it.pubKey == proposer })
    }

    @Test
    fun `a chapter of the longest allowed length still proposes`() = runBlocking {
        openDevice()

        val events = itemEvents(
            proposeChapter(paragraphs(FrostSigningManager.MAX_BATCH_SIZE - 1)).id
        )

        // The arithmetic the form is built on: the chapter takes one of the
        // batch's places and the paragraphs get the rest. If this stopped being
        // true, `AddChapterScreen` would offer a chapter the session then refuses.
        assertEquals(FrostSigningManager.MAX_BATCH_SIZE, events.size)
    }

    @Test
    fun `a chapter of one paragraph too many is refused`() = runBlocking {
        openDevice()

        // Refused here rather than truncated: a batch that quietly dropped its
        // last paragraphs would sign a chapter whose text nobody holds the
        // chunks for. The screen checks first so this is never what a member
        // sees, but the screen is not what enforces it.
        assertFailsWith<IllegalArgumentException> {
            proposeChapter(paragraphs(FrostSigningManager.MAX_BATCH_SIZE))
        }

        assertEquals(emptyList(), db.frostSigningSessionDao().getSessionsForChatRoom(roomId))
    }
}
