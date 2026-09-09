package com.it.curated.compose.nostr.nip30303

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import com.it.curated.compose.nostr.nip30303.tags.OriginalTextTag
import com.it.curated.compose.text.Markdown

/**
 * The chunks a chapter is proposed with are cut from the chapter itself.
 *
 * A chapter and a chunk per paragraph go to the group as one batch, and the
 * chunks can only be built once the chapter has been authored under the group's
 * key — a chunk carries the chapter's id, and that id does not exist before
 * then. So the split takes the unsigned chapter the session is about to sign
 * and cuts from what it holds.
 *
 * What breaks quietly here is a chunk that names the wrong chapter, or none:
 * every id in sight is opaque hex, the batch signs whatever it is handed, and
 * the first sign of trouble is a chapter that arrives with no chunks under it
 * because every one of them failed a foreign key on the way in.
 */
class ChapterChunkSplitTest {
    private val groupKey = "a".repeat(64)
    private val artifactVersionId = "b".repeat(64)

    private val twoParagraphs = "The first paragraph.\n\nThe second paragraph."

    /**
     * The chapter as the session will sign it: the proposer's fields re-authored
     * under the group's key, with the id computed from them.
     */
    private fun unsignedChapter(
        name: String = "Chapter 1",
        originalText: String = twoParagraphs,
        index: Int = 0,
        createdAt: Long = 1_700_000_000,
    ): Event {
        val template = ChapterEvent.build(
            artifactVersionId = artifactVersionId,
            name = name,
            originalText = originalText,
            index = index,
            wordCount = Markdown.wordCount(originalText),
            characterCount = Markdown.characterCount(originalText),
            createdAt = createdAt,
        )

        return Event(
            id = EventHasher.hashId(
                pubKey = groupKey,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content
            ),
            pubKey = groupKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = ""
        )
    }

    /** What a chunk template says once it is tags and a string, which is all it travels as. */
    private fun readBack(template: com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<ChunkEvent>) =
        ChunkEvent(
            id = EventHasher.hashId(
                pubKey = groupKey,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content
            ),
            pubKey = groupKey,
            createdAt = template.createdAt,
            tags = template.tags,
            content = template.content,
            sig = ""
        )

    @Test
    fun `every chunk names the chapter it was cut from`() {
        // The whole reason the split happens against the built chapter rather
        // than against the form. A chunk naming anything else is a row that
        // cannot be written.
        val chapter = unsignedChapter()

        val chunks = ChunkEvent.splitOf(chapter).map(::readBack)

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.chapterId() == chapter.id })
    }

    @Test
    fun `the chunks carry the chapter's text in order`() {
        val chunks = ChunkEvent.splitOf(unsignedChapter()).map(::readBack)

        assertEquals(listOf("The first paragraph.", "The second paragraph."), chunks.map { it.content })
        assertEquals(listOf(0, 1), chunks.map { it.index() })
    }

    @Test
    fun `each chunk counts its own paragraph rather than the chapter's`() {
        // The chapter's counts describe the whole text. A chunk that inherited
        // them would report every paragraph as the length of the chapter, and
        // any progress measured per chunk would be wrong by that factor.
        val chunks = ChunkEvent
            .splitOf(unsignedChapter(originalText = "One two three.\n\nFour."))
            .map(::readBack)

        assertEquals(listOf(3, 1), chunks.map { it.wordCount() })
        assertEquals(listOf("One two three.".length, "Four.".length), chunks.map { it.characterCount() })
    }

    @Test
    fun `the chunks are signed as of the chapter's own moment`() {
        // Reading the clock here would put the batch's items minutes apart on a
        // slow device, and a chapter signed at one time with chunks stamped at
        // another is a batch that reads as two events that happened to share a
        // session.
        val chapter = unsignedChapter(createdAt = 1_700_000_000)

        val chunks = ChunkEvent.splitOf(chapter)

        assertTrue(chunks.all { it.createdAt == 1_700_000_000L })
    }

    @Test
    fun `two identical paragraphs stay two chunks`() {
        // Ids are content hashes, so repeated text is where two chunks would
        // collide and the batch would carry one where the chapter has two. The
        // position is in the event, which is what keeps them apart.
        val chunks = ChunkEvent
            .splitOf(unsignedChapter(originalText = "Again.\n\nAgain."))
            .map(::readBack)

        assertEquals(2, chunks.size)
        assertNotEquals(chunks[0].id, chunks[1].id)
    }

    @Test
    fun `chapters that differ split into chunks that differ`() {
        // Two chapters with the same words are two chapters, and their chunks
        // belong to their own. Chunks of chapter three must not be chunks of
        // chapter one.
        val first = unsignedChapter(index = 0)
        val second = unsignedChapter(index = 3)

        val firstChunks = ChunkEvent.splitOf(first).map(::readBack)
        val secondChunks = ChunkEvent.splitOf(second).map(::readBack)

        assertNotEquals(first.id, second.id)
        assertEquals(firstChunks.map { it.content }, secondChunks.map { it.content })
        assertNotEquals(firstChunks.map { it.id }, secondChunks.map { it.id })
    }

    @Test
    fun `the split counts what the form counted`() {
        // The screen shows a chunk count as the text is typed and refuses to
        // propose past the batch cap on the strength of it. If the split
        // disagreed with that count, the form would allow a batch the session
        // then rejects -- or refuse one it would have taken.
        val text = "One.\n\nTwo.\n\n\nThree.\n\n   \n\nFour."

        assertEquals(
            Markdown.splitParagraphs(text).size,
            ChunkEvent.splitOf(unsignedChapter(originalText = text)).size
        )
    }

    @Test
    fun `a chapter with no text to split proposes no chunks`() {
        assertEquals(emptyList(), ChunkEvent.splitOf(unsignedChapter(originalText = "   ")))

        // And a chapter that declares no text at all, which is malformed rather
        // than empty: nothing to split, and nothing to throw over either.
        val declared = unsignedChapter()
        val silent = Event(
            id = declared.id,
            pubKey = declared.pubKey,
            createdAt = declared.createdAt,
            kind = declared.kind,
            tags = declared.tags.filterNot { it.firstOrNull() == OriginalTextTag.TAG_NAME }
                .toTypedArray(),
            content = declared.content,
            sig = declared.sig
        )

        assertEquals(emptyList(), ChunkEvent.splitOf(silent))
    }
}
