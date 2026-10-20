package com.machankura.bennu.compose.database.model

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlinx.coroutines.runBlocking
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.nostr.nip30303.TranslationChunkEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What happens when a chunk is translated twice.
 *
 * A translation chunk is not edited. Retranslating a passage means proposing a
 * new event carrying new words, and an event's id is a hash over its content --
 * so the second translation arrives as a row of its own rather than as an
 * overwrite of the first. Left alone, the chapter would hold two answers to
 * what one passage says, and the table pairing chunks with their translations
 * would show whichever the query reached first.
 *
 * The rule this asserts is that the newest one wins, decided by the timestamp
 * the group signed at rather than by arrival: two devices catching up read the
 * same events in whatever order the relay hands them over, and they have to end
 * up holding the same translation either way.
 */
class TranslationChunkApplyJvmTest {

    private val db: BennuDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<BennuDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val author = "a".repeat(64)
    private val roomId = "b".repeat(64)
    private val chunkId = "c".repeat(64)
    private val translationChapterId = "d".repeat(64)
    private val signature = "0".repeat(128)

    /**
     * Everything a translation chunk hangs off, seeded as already signed: the
     * room, the artifact it belongs to, the chapter and chunk it translates, and
     * the translation chapter it goes into. None of it is what this test is
     * about; they are the foreign keys in the way.
     */
    private suspend fun seed() {
        val nostrEventId = "e".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = author,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = signature,
            )
        )
        db.profileDao().upsert(Profile(publicKey = author, nostrEventId = nostrEventId))
        db.chatRoomDao().upsert(
            ChatRoom(
                id = roomId,
                userPublicKey = author,
                subject = "a translation room",
                description = null,
                mlsGroupState = null,
            )
        )

        val dialectId = "1".repeat(64)
        db.mantraDialectDao().upsert(
            MantraDialect(
                id = dialectId,
                publicKey = roomId,
                name = "Sesotho",
                country = "ZA",
                language = "st",
                signature = signature,
                chatRoomId = roomId,
            )
        )

        val artifactId = "2".repeat(64)
        db.mantraArtifactDao().upsert(
            MantraArtifact(
                id = artifactId,
                publicKey = roomId,
                name = "To Kill a Mockingbird",
                url = "https://harper.com/2-kill-Bird",
                visibility = "private",
                dialectId = dialectId,
                license = "cc",
                chatRoomId = roomId,
                signature = signature,
            )
        )

        val artifactVersionId = "3".repeat(64)
        db.mantraArtifactVersionDao().upsert(
            MantraArtifactVersion(
                id = artifactVersionId,
                artifactId = artifactId,
                publicKey = roomId,
                versionLabel = "1.0",
                signature = signature,
                chatRoomId = roomId,
            )
        )

        val chapterId = "4".repeat(64)
        db.mantraChapterDao().upsert(
            MantraChapter(
                id = chapterId,
                artifactVersionId = artifactVersionId,
                publicKey = roomId,
                name = "Chapter 1",
                originalText = "When he was nearly thirteen.",
                index = 0,
                wordCount = 5,
                characterCount = 28,
                signature = signature,
                chatRoomId = roomId,
            )
        )

        db.mantraChunkDao().upsert(
            MantraChunk(
                id = chunkId,
                chapterId = chapterId,
                publicKey = roomId,
                text = "When he was nearly thirteen.",
                index = 0,
                wordCount = 5,
                characterCount = 28,
                signature = signature,
                chatRoomId = roomId,
            )
        )

        val translationId = "5".repeat(64)
        db.mantraTranslationArtifactVersionDao().upsert(
            MantraTranslationArtifactVersion(
                id = translationId,
                publicKey = roomId,
                artifactVersionId = artifactVersionId,
                dialectId = dialectId,
                name = "Sesotho",
                visibility = "private",
                license = "cc",
                signature = signature,
                chatRoomId = roomId,
            )
        )

        db.mantraTranslationChapterDao().upsert(
            MantraTranslationChapter(
                id = translationChapterId,
                publicKey = roomId,
                chapterId = chapterId,
                translationArtifactVersionId = translationId,
                index = 0,
                signature = signature,
                chatRoomId = roomId,
            )
        )
    }

    /** A translation of the seeded chunk, signed by the room as the group would. */
    private fun translation(text: String, createdAt: Long): Event {
        val template = TranslationChunkEvent.build(
            translationChapterId = translationChapterId,
            chunkId = chunkId,
            index = 0,
            text = text,
            createdAt = createdAt,
        )

        return Event(
            id = EventHasher.hashId(
                pubKey = roomId,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content,
            ),
            pubKey = roomId,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = signature,
        )
    }

    private suspend fun apply(event: Event) = ChatMessage.applyInnerEvent(
        database = db,
        groupId = roomId,
        event = event,
        marmotGroupEventId = null,
        marmotInnerEventId = null,
        senderPublicKey = roomId,
        isUserMessage = false,
        createdAt = Instant.fromEpochSeconds(event.createdAt),
    )

    private suspend fun translations() = db.mantraTranslationChunkDao()
        .getTranslationChunksByTranslationChapterId(translationChapterId)

    @Test
    fun `a retranslation replaces the translation it supersedes`() = runBlocking {
        seed()

        val signedAt = Clock.System.now().epochSeconds
        apply(translation("Ha a le hantle lilemo li leshome le metso e meraro.", signedAt))
        apply(translation("Ha a batla ho ba lilemo li leshome le metso e meraro.", signedAt + 60))

        // Both are valid signatures of the group's over the same passage, and
        // the chapter has room for one of them.
        val kept = translations().single()
        assertEquals("Ha a batla ho ba lilemo li leshome le metso e meraro.", kept.text)
    }

    @Test
    fun `a translation arriving after the one that superseded it is dropped`() = runBlocking {
        seed()

        val signedAt = Clock.System.now().epochSeconds
        val first = translation("Ha a le hantle lilemo li leshome le metso e meraro.", signedAt)
        val second = translation("Ha a batla ho ba lilemo li leshome le metso e meraro.", signedAt + 60)

        // The order the relay happened to hand them over in, which is not the
        // order the group signed them in. A device catching up has to reach the
        // same chapter as one that saw them the other way round.
        apply(second)
        apply(first)

        val kept = translations().single()
        assertEquals(second.id, kept.id)
    }

    @Test
    fun `two chunks of the same chapter each keep their translation`() = runBlocking {
        seed()

        // The rule is one translation per source chunk, not one per chapter.
        // Matching on the chapter alone would leave a chapter that can only ever
        // hold its most recently translated passage.
        val otherChunkId = "f".repeat(64)
        db.mantraChunkDao().upsert(
            MantraChunk(
                id = otherChunkId,
                chapterId = "4".repeat(64),
                publicKey = roomId,
                text = "My brother Jem got his arm badly broken.",
                index = 1,
                wordCount = 8,
                characterCount = 40,
                signature = signature,
                chatRoomId = roomId,
            )
        )

        val signedAt = Clock.System.now().epochSeconds
        apply(translation("Ha a le hantle lilemo li leshome le metso e meraro.", signedAt))

        val otherTemplate = TranslationChunkEvent.build(
            translationChapterId = translationChapterId,
            chunkId = otherChunkId,
            index = 1,
            text = "Moholoane oa ka Jem o ile a robeha letsoho.",
            createdAt = signedAt + 60,
        )
        apply(
            Event(
                id = EventHasher.hashId(
                    pubKey = roomId,
                    createdAt = otherTemplate.createdAt,
                    kind = otherTemplate.kind,
                    tags = otherTemplate.tags,
                    content = otherTemplate.content,
                ),
                pubKey = roomId,
                createdAt = otherTemplate.createdAt,
                kind = otherTemplate.kind,
                tags = otherTemplate.tags,
                content = otherTemplate.content,
                sig = signature,
            )
        )

        assertEquals(
            listOf(chunkId, otherChunkId),
            translations().map { it.chunkId },
        )
    }
}
