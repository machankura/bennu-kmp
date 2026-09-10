package to.curare.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.builder.getRoomDatabase
import to.curare.compose.database.model.ChatMessage
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.DkgParticipantMessage
import to.curare.compose.database.model.DkgSession
import to.curare.compose.database.model.MantraChapter
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.types.DkgRitualStage
import to.curare.compose.extensions.toHex
import to.curare.compose.nostr.dkg.DkgRitualEvents
import to.curare.compose.nostr.nip30303.ChapterEvent
import to.curare.compose.nostr.nip30303.ChunkEvent
import to.curare.compose.nostr.nip30303.TranslationArtifactVersionEvent
import to.curare.compose.nostr.nip30303.TranslationChapterEvent
import to.curare.compose.text.Markdown
import to.curare.compose.ui.view.model.AddChapterViewModel
import to.curare.compose.ui.view.model.AddTranslationArtifactVersionViewModel

/**
 * What a translation proposal actually puts in front of the group.
 *
 * The same sharp edge `ChapterBatchProposalJvmTest` covers for a chapter and
 * its chunks, one level up: a translation chapter carries the id of the
 * translation it belongs to, and that id does not exist until the proposal
 * authors the translation under the group's key at the room's derivation path.
 * Both are resolved inside `proposeSigningBatch` and neither is knowable to the
 * caller, which is why the chapters are built from what it hands back.
 *
 * If that came apart, the translation would still be signed and every chapter
 * would still verify -- against a translation id nobody has. They would fail a
 * foreign key on the way in and the translation would simply arrive with no
 * chapters, which is a translation nobody can start.
 */
class TranslationBatchProposalJvmTest {
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
    private val dialectId = "d".repeat(64)

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

    /**
     * The source chapters, as the artifact already holds them. Only the id and
     * the position travel into the translation, so nothing else is furnished.
     */
    private fun sourceChapters(count: Int): List<MantraChapter> = (0 until count).map { index ->
        MantraChapter(
            id = "c$index".padEnd(64, 'f'),
            artifactVersionId = artifactVersionId,
            publicKey = roomId,
            name = "Chapter ${index + 1}",
            originalText = "Paragraph ${index + 1}.",
            index = index,
            wordCount = 2,
            characterCount = 13,
            signature = "0".repeat(128),
            chatRoomId = roomId
        )
    }

    private val translationTemplate = TranslationArtifactVersionEvent.build(
        artifactVersionId = artifactVersionId,
        dialectId = dialectId,
        name = "Sesotho",
        visibility = "private",
        license = "cc",
    )

    /** Exactly what `AddTranslationArtifactVersionViewModel.addTranslation` proposes. */
    private suspend fun proposeTranslation(chapters: List<MantraChapter>) =
        FrostSigningManager.proposeSigningBatch(
            database = db,
            localChatRoom = room,
            userPublicKey = proposer,
            lead = translationTemplate,
            dependents = { translation ->
                TranslationScaffold.chaptersOf(
                    translationArtifactVersionIds = listOf(translation.id),
                    chapters = chapters.map(TranslationScaffold.SourceChapter::of),
                    createdAt = translation.createdAt,
                )
            }
        )

    private suspend fun itemEvents(sessionId: String): List<Event> =
        db.frostSigningSessionDao().getItems(sessionId).map { Event.fromJson(it.unsignedEventJson) }

    @Test
    fun `the translation leads the batch and its chapters follow`() = runBlocking {
        openDevice()

        val events = itemEvents(proposeTranslation(sourceChapters(3)).id)

        // Item order is apply order, and a translation chapter row whose
        // translation does not exist yet is a foreign key violation. The
        // translation has to be first.
        assertEquals(4, events.size)
        assertEquals(TranslationArtifactVersionEvent.KIND, events.first().kind)
        assertTrue(events.drop(1).all { it.kind == TranslationChapterEvent.KIND })
    }

    @Test
    fun `every chapter names the translation as the group will author it`() = runBlocking {
        openDevice()

        val events = itemEvents(proposeTranslation(sourceChapters(3)).id)
        val translation = events.first()

        // The claim the whole `lead`/`dependents` form exists for. The id here is
        // a hash over the group's key at the room's path; a caller that built the
        // chapters before proposing could not have known it.
        val chapters = events.drop(1).map {
            TranslationChapterEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
        }

        assertTrue(chapters.all { it.translationArtifactVersionId() == translation.id })
    }

    @Test
    fun `each chapter keeps the source chapter it stands in for, and its place`() = runBlocking {
        openDevice()

        val source = sourceChapters(3)
        val events = itemEvents(proposeTranslation(source).id)
        val chapters = events.drop(1).map {
            TranslationChapterEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
        }

        // A translation chapter is only ever a place to hang translated chunks
        // off the right source chapter, in the right order. Lose either and the
        // translation reads as some other book.
        assertEquals(source.map { it.id }, chapters.map { it.chapterId() })
        assertEquals(source.map { it.index }, chapters.map { it.index() })
    }

    @Test
    fun `the whole batch is stamped and authored as one act by the room`() = runBlocking {
        openDevice()

        val events = itemEvents(proposeTranslation(sourceChapters(3)).id)

        // Signing runs at the path the room was derived at, so the author of
        // every item is the room's own id -- chapters included, which is what
        // lets one be checked without being told which key to expect.
        assertTrue(events.all { it.pubKey == roomId })
        assertTrue(events.none { it.pubKey == proposer })

        // And they carry the translation's timestamp rather than each reading
        // the clock, so the batch is one act rather than events that happen to
        // share a session.
        assertEquals(listOf(events.first().createdAt), events.map { it.createdAt }.distinct())
    }

    @Test
    fun `an artifact of the most chapters allowed still proposes`() = runBlocking {
        openDevice()

        val events = itemEvents(
            proposeTranslation(sourceChapters(FrostSigningManager.MAX_BATCH_SIZE - 1)).id
        )

        // The arithmetic the form is built on: the translation takes one of the
        // batch's places and the chapters get the rest. If this stopped being
        // true, the screen would offer a translation the session then refuses.
        assertEquals(
            FrostSigningManager.MAX_BATCH_SIZE,
            AddTranslationArtifactVersionViewModel.MAX_CHAPTERS_PER_TRANSLATION + 1
        )
        assertEquals(FrostSigningManager.MAX_BATCH_SIZE, events.size)
    }

    /** Exactly what `AddChapterViewModel.addChapter` proposes: the chapter, then its chunks. */
    private suspend fun proposeChapter(paragraphs: Int, index: Int = 0) =
        FrostSigningManager.proposeSigningBatch(
            database = db,
            localChatRoom = room,
            userPublicKey = proposer,
            lead = (1..paragraphs).joinToString("\n\n") { "Paragraph $it." }.let { text ->
                ChapterEvent.build(
                    artifactVersionId = artifactVersionId,
                    name = "Chapter ${index + 1}",
                    originalText = text,
                    index = index,
                    wordCount = Markdown.wordCount(text),
                    characterCount = Markdown.characterCount(text),
                )
            },
            dependents = ChunkEvent::splitOf
        )

    /** And what it proposes straight afterwards: that chapter, into every translation. */
    private suspend fun proposeScaffolding(chapter: Event, index: Int, translationIds: List<String>) =
        FrostSigningManager.proposeSigningBatch(
            database = db,
            localChatRoom = room,
            userPublicKey = proposer,
            events = TranslationScaffold.chaptersOf(
                translationArtifactVersionIds = translationIds,
                chapters = listOf(TranslationScaffold.SourceChapter(id = chapter.id, index = index)),
                createdAt = chapter.createdAt,
            )
        )

    @Test
    fun `a chapter's scaffolding runs beside it as a session of its own`() = runBlocking {
        openDevice()

        val translationIds = listOf("1".repeat(64), "2".repeat(64), "3".repeat(64))

        val chapterSession = proposeChapter(paragraphs = 2)
        val chapter = itemEvents(chapterSession.id).first()
        val scaffoldSession = proposeScaffolding(chapter, index = 0, translationIds = translationIds)

        // Two live sessions in one room, which is the whole point: every FROST
        // message carries the session it belongs to, so neither is the room's
        // "current" one and nothing had to be finished before the second
        // opened.
        assertNotEquals(chapterSession.id, scaffoldSession.id)
        assertEquals(2, db.frostSigningSessionDao().getSessionsForChatRoom(roomId).size)

        // The chapter's batch is what it would have been with no translations
        // at all: the chapter and a chunk per paragraph, and nothing else.
        val chapterItems = itemEvents(chapterSession.id)
        assertEquals(3, chapterItems.size)
        assertEquals(ChapterEvent.KIND, chapterItems.first().kind)
        assertTrue(chapterItems.drop(1).all { it.kind == ChunkEvent.KIND })

        // And the scaffolding names that chapter, in every translation.
        val scaffolded = itemEvents(scaffoldSession.id).map {
            TranslationChapterEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
        }
        assertEquals(translationIds, scaffolded.map { it.translationArtifactVersionId() })
        assertTrue(scaffolded.all { it.chapterId() == chapter.id })
    }

    @Test
    fun `each session's transcript lines name it, and never the other`() = runBlocking {
        openDevice()

        val chapterSession = proposeChapter(paragraphs = 2)
        val chapter = itemEvents(chapterSession.id).first()
        val scaffoldSession = proposeScaffolding(
            chapter,
            index = 0,
            translationIds = listOf("1".repeat(64))
        )

        val lines = db.chatMessageDao().getChatMessagesByChatRoomId(roomId)
            .map { it.chatMessage }
            .filter { it.messageType in ChatMessage.FROST_TYPES }

        // Both sessions announce themselves and both publish this device's nonce,
        // so the transcript holds one session's lines interleaved with the other's
        // -- and every line says which of the two it belongs to.
        val bySession = lines.groupBy { it.frostSigningSessionId }

        assertEquals(setOf(chapterSession.id, scaffoldSession.id), bySession.keys)
        assertTrue(bySession.values.all { it.isNotEmpty() })

        // Which is what the transcript's rules are read through. Nothing else on
        // these rows can tell them apart: the two sessions are running at the same
        // time and write the same types, so before the sessions were on the rows a
        // line of one was read as evidence about the other -- and the reader's
        // second proposal lost its "Review" the moment the first was answered.
        assertTrue(
            ChatMessage.answeredRequests(lines).isEmpty(),
            "a proposer is never asked to approve their own proposal"
        )
    }

    @Test
    fun `the two sessions never share nonce material`() = runBlocking {
        openDevice()

        val chapterSession = proposeChapter(paragraphs = 2)
        val chapter = itemEvents(chapterSession.id).first()
        val scaffoldSession = proposeScaffolding(
            chapter,
            index = 0,
            translationIds = listOf("1".repeat(64), "2".repeat(64))
        )

        // The one thing concurrency could get wrong. Two partial signatures
        // under a single secret nonce are two equations in one unknown, and the
        // share falls out; seeds are minted per item per session precisely so
        // that running two at once cannot arrange it.
        val seeds = (
            db.frostSigningSessionDao().getItems(chapterSession.id) +
                db.frostSigningSessionDao().getItems(scaffoldSession.id)
            ).map { it.nonceRandom }

        assertEquals(5, seeds.size)
        assertEquals(seeds.size, seeds.distinct().size)
    }

    @Test
    fun `the caps do not compete`() = runBlocking {
        openDevice()

        // The reason the scaffolding is a second session rather than more items
        // on the chapter's. A chapter of the longest allowed length still
        // proposes with translations waiting for it; sharing one batch, every
        // dialect the group worked in would have cost it a paragraph.
        val translationIds = (1..8).map { it.toString().repeat(64).take(64) }

        val chapterSession = proposeChapter(paragraphs = AddChapterViewModel.MAX_CHUNKS_PER_CHAPTER)
        val chapter = itemEvents(chapterSession.id).first()
        val scaffoldSession = proposeScaffolding(chapter, index = 0, translationIds = translationIds)

        assertEquals(FrostSigningManager.MAX_BATCH_SIZE, itemEvents(chapterSession.id).size)
        assertEquals(translationIds.size, itemEvents(scaffoldSession.id).size)
    }

    @Test
    fun `an artifact longer than one session is split across two`() = runBlocking {
        openDevice()

        // What `addTranslation` does with an artifact past the cap: the
        // translation and as many chapters as fit, then the rest naming the
        // translation the first session is about to sign. Refusing instead
        // would be a cap on how long a book may be, dressed up as a cap on a
        // batch.
        val chapters = sourceChapters(AddTranslationArtifactVersionViewModel.MAX_CHAPTERS_PER_TRANSLATION + 3)

        val first = proposeTranslation(
            chapters.take(AddTranslationArtifactVersionViewModel.MAX_CHAPTERS_PER_TRANSLATION)
        )
        val translation = itemEvents(first.id).first()
        val second = FrostSigningManager.proposeSigningBatch(
            database = db,
            localChatRoom = room,
            userPublicKey = proposer,
            events = TranslationScaffold.chaptersOf(
                translationArtifactVersionIds = listOf(translation.id),
                chapters = chapters.drop(AddTranslationArtifactVersionViewModel.MAX_CHAPTERS_PER_TRANSLATION)
                    .map(TranslationScaffold.SourceChapter::of),
                createdAt = translation.createdAt,
            )
        )

        assertEquals(FrostSigningManager.MAX_BATCH_SIZE, itemEvents(first.id).size)
        assertEquals(3, itemEvents(second.id).size)

        // Every chapter of the work is covered exactly once, across both, and
        // each names the translation as the group will author it.
        val scaffolded = (itemEvents(first.id).drop(1) + itemEvents(second.id)).map {
            TranslationChapterEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
        }
        assertEquals(chapters.map { it.id }, scaffolded.map { it.chapterId() })
        assertEquals(chapters.map { it.index }, scaffolded.map { it.index() })
        assertTrue(scaffolded.all { it.translationArtifactVersionId() == translation.id })
    }

    @Test
    fun `one session is still refused a chapter too many`() = runBlocking {
        openDevice()

        // Refused rather than truncated: a batch that quietly dropped its last
        // chapters would sign a translation the group believes covers the whole
        // work while the end of it can never be translated. The screen splits
        // before it gets here, so this is never what a member sees -- but the
        // screen is not what enforces it.
        assertFailsWith<IllegalArgumentException> {
            proposeTranslation(sourceChapters(FrostSigningManager.MAX_BATCH_SIZE))
        }

        assertEquals(emptyList(), db.frostSigningSessionDao().getSessionsForChatRoom(roomId))
    }
}
