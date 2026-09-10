package to.curare.compose.database.model

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import to.curare.compose.nostr.nip30303.ArtifactEvent
import to.curare.compose.nostr.nip30303.ArtifactVersionEvent
import to.curare.compose.nostr.nip30303.ChapterEvent
import to.curare.compose.nostr.nip30303.ChunkEvent
import to.curare.compose.nostr.nip30303.DialectEvent
import to.curare.compose.nostr.nip30303.SubmissionEvent
import to.curare.compose.nostr.nip30303.TranslationArtifactVersionEvent
import to.curare.compose.nostr.nip30303.tags.ArtifactIdTag

/**
 * The row on disk and the payload on the wire have to be the same event.
 *
 * `MantraDao` writes an entity whose id comes from `MantraX.fromXEventTemplate`,
 * and separately builds the rumor it submits with `rumorOf`, which hashes the
 * template itself. Both are supposed to produce one id. Nothing checks that they
 * do, and nothing would notice if they stopped:
 *
 *  - the submission would carry a `payloadId` naming an event nobody has,
 *  - `MarmotInnerEvent.payloadEventId` would stop matching the row it carries,
 *    so `deleteByPayloadEventId` would silently un-queue nothing and superseded
 *    translations would go out anyway,
 *  - and every receiver would create a *second* row rather than converging on
 *    the sender's, because entity ids are content hashes and the two sides would
 *    be hashing different things.
 *
 * All of that is silent. The ids are opaque hex either way.
 */
class RumorIdAgreementTest {
    private val author = "a".repeat(64)
    private val chatRoomId = "room"
    private val other = "b".repeat(64)

    /** Exactly what `MantraDao.rumorOf` does, and it must stay exactly that. */
    private fun rumorIdOf(template: EventTemplate<*>): String = EventHasher.hashId(
        pubKey = author,
        createdAt = template.createdAt,
        kind = template.kind,
        tags = template.tags,
        content = template.content
    )

    @Test
    fun `a dialect's row and its rumor agree`() {
        val template = DialectEvent.build(name = "Sesotho", country = "Lesotho", language = "st")

        val entity = MantraDialect.fromDialectEventTemplate(
            dialectEventTemplate = template,
            chatRoomId = chatRoomId,
            userPublicKey = author
        )

        assertEquals(rumorIdOf(template), entity?.id)
    }

    @Test
    fun `an artifact's row and its rumor agree`() {
        val template = ArtifactEvent.build(
            name = "In Detention",
            url = "example.com",
            visibility = "private",
            license = "cc",
            dialectId = other,
            versionLabel = "1.0"
        )

        val entity = MantraArtifact.fromArtifactEventTemplate(
            artifactEventTemplate = template,
            chatRoomId = chatRoomId,
            userPublicKey = author
        )

        assertEquals(rumorIdOf(template), entity?.id)
    }

    @Test
    fun `an artifact version's row and its rumor agree`() {
        val template = ArtifactVersionEvent.build(content = "1.0") {
            addUnique(ArtifactIdTag.assemble(other))
        }

        val entity = MantraArtifactVersion.fromArtifactVersionEventTemplate(
            artifactVersionEventTemplate = template,
            chatRoomId = chatRoomId,
            userPublicKey = author
        )

        assertEquals(rumorIdOf(template), entity?.id)
    }

    @Test
    fun `a chapter's and a chunk's rows agree with their rumors`() {
        val chapter = ChapterEvent.build(
            artifactVersionId = other,
            name = "Chapter 1",
            originalText = "some text",
            index = 0,
            wordCount = 2,
            characterCount = 9
        )
        val chunk = ChunkEvent.build(
            chapterId = other,
            text = "some text",
            index = 0,
            wordCount = 2,
            characterCount = 9
        )

        assertEquals(
            rumorIdOf(chapter),
            MantraChapter.fromChapterEventTemplate(chapter, chatRoomId, author)?.id
        )
        assertEquals(
            rumorIdOf(chunk),
            MantraChunk.fromChunkEventTemplate(chunk, chatRoomId, author)?.id
        )
    }

    @Test
    fun `a translation version's row and its rumor agree`() {
        val template = TranslationArtifactVersionEvent.build(
            artifactVersionId = other,
            dialectId = other,
            name = "Sesotho",
            visibility = "private",
            license = "cc"
        )

        val entity = MantraTranslationArtifactVersion.fromTranslationArtifactVersionEventTemplate(
            translationArtifactVersionEventTemplate = template,
            chatRoomId = chatRoomId,
            userPublicKey = author
        )

        assertEquals(rumorIdOf(template), entity?.id)
    }

    @Test
    fun `the submission names the id the row was written under`() {
        // The end of the chain the rest of this file checks a link of: what a
        // receiver reads out of the envelope has to be the id the sender stored.
        val template = DialectEvent.build(name = "Sesotho", country = "Lesotho", language = "st")
        val entity = MantraDialect.fromDialectEventTemplate(template, chatRoomId, author)

        val payload = Event(
            id = rumorIdOf(template),
            pubKey = author,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = ""
        )
        val submission = SubmissionEvent.build(payload = payload)

        val readBack = SubmissionEvent(
            id = "f".repeat(64),
            pubKey = author,
            createdAt = submission.createdAt,
            tags = submission.tags,
            content = submission.content,
            sig = ""
        )

        assertEquals(entity?.id, readBack.payloadId())
        assertEquals(entity?.id, readBack.payload()?.id)
        assertEquals(DialectEvent.KIND, readBack.payloadKind())
    }
}
