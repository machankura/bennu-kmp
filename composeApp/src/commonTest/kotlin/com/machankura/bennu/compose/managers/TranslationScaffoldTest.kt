package com.machankura.bennu.compose.managers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.machankura.bennu.compose.database.model.MantraChapter
import com.machankura.bennu.compose.database.model.MantraTranslationChapter
import com.machankura.bennu.compose.nostr.nip30303.TranslationChapterEvent
import com.machankura.bennu.compose.nostr.nip30303.tags.ChapterIdTag
import com.machankura.bennu.compose.nostr.nip30303.tags.IndexTag
import com.machankura.bennu.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag

/**
 * The join between a translation and the chapters it is of, as a pure function.
 *
 * Both ends arrive on their own schedule, so this is built from three
 * directions -- a translation being proposed, a chapter being proposed, and a
 * catch-up filling in what the two missed. What it must not do is disagree with
 * itself about what a translation covers depending on which direction it was
 * built from.
 */
class TranslationScaffoldTest {

    private val translationA = "a".repeat(64)
    private val translationB = "b".repeat(64)

    private fun sourceChapter(index: Int, id: String = "c$index".padEnd(64, 'f')) = MantraChapter(
        id = id,
        artifactVersionId = "v".repeat(64),
        publicKey = "p".repeat(64),
        name = "Chapter ${index + 1}",
        originalText = "Paragraph.",
        index = index,
        wordCount = 1,
        characterCount = 10,
        signature = "",
        chatRoomId = "r".repeat(64),
    )

    private fun translationChapterFor(chapter: MantraChapter, translationId: String) =
        MantraTranslationChapter(
            id = "t${chapter.id}".take(64),
            publicKey = "p".repeat(64),
            chapterId = chapter.id,
            translationArtifactVersionId = translationId,
            index = chapter.index,
            signature = "",
            chatRoomId = "r".repeat(64),
        )

    private fun parse(templates: List<com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<TranslationChapterEvent>>) =
        templates.map { template ->
            Triple(
                template.tags.firstNotNullOfOrNull(TranslationArtifactVersionIdTag::parse)?.ref?.eventId,
                template.tags.firstNotNullOfOrNull(ChapterIdTag::parse)?.ref?.eventId,
                template.tags.firstNotNullOfOrNull(IndexTag::parse)?.index,
            )
        }

    @Test
    fun `a translation's chapters stay together and in reading order`() {
        val chapters = (0..2).map(::sourceChapter)

        val templates = TranslationScaffold.chaptersOf(
            translationArtifactVersionIds = listOf(translationA, translationB),
            chapters = chapters.map(TranslationScaffold.SourceChapter::of),
            createdAt = 1_000,
        )

        // Item order is the order a session signs and applies in, so it is
        // fixed here rather than left to a caller. Translations outer, chapters
        // inner: one translation's chapters are contiguous and in order.
        assertEquals(
            listOf(
                Triple(translationA, chapters[0].id, 0),
                Triple(translationA, chapters[1].id, 1),
                Triple(translationA, chapters[2].id, 2),
                Triple(translationB, chapters[0].id, 0),
                Triple(translationB, chapters[1].id, 1),
                Triple(translationB, chapters[2].id, 2),
            ),
            parse(templates)
        )
    }

    @Test
    fun `a chapter proposed on its own is scaffolded into every translation`() {
        // The other direction: one chapter, every existing translation. This is
        // what a chapter's own scaffolding session carries.
        val templates = TranslationScaffold.chaptersOf(
            translationArtifactVersionIds = listOf(translationA, translationB),
            chapters = listOf(TranslationScaffold.SourceChapter(id = "z".repeat(64), index = 7)),
            createdAt = 1_000,
        )

        assertEquals(
            listOf(
                Triple(translationA, "z".repeat(64), 7),
                Triple(translationB, "z".repeat(64), 7),
            ),
            parse(templates)
        )
    }

    @Test
    fun `a catch-up keeps each chapter's own position not the position in the gap`() {
        val chapters = (0..5).map(::sourceChapter)

        // A catch-up proposes a subset, and the subset is not contiguous.
        // Counting the list would renumber chapters 3 and 5 as 1 and 2, which
        // is a translation that reads in a different order than the work.
        val templates = TranslationScaffold.chaptersOf(
            translationArtifactVersionIds = listOf(translationA),
            chapters = listOf(chapters[3], chapters[5]).map(TranslationScaffold.SourceChapter::of),
            createdAt = 1_000,
        )

        assertEquals(listOf(3, 5), parse(templates).map { it.third })
    }

    @Test
    fun `the whole scaffolding carries one timestamp`() {
        val templates = TranslationScaffold.chaptersOf(
            translationArtifactVersionIds = listOf(translationA, translationB),
            chapters = (0..2).map(::sourceChapter).map(TranslationScaffold.SourceChapter::of),
            createdAt = 4_242,
        )

        // Read once by the caller rather than per template, so a scaffolding
        // proposed as one act reads as one.
        assertEquals(listOf(4_242L), templates.map { it.createdAt }.distinct())
    }

    @Test
    fun `nothing to scaffold when there is nothing on one side`() {
        assertTrue(
            TranslationScaffold.chaptersOf(
                translationArtifactVersionIds = emptyList(),
                chapters = listOf(TranslationScaffold.SourceChapter("z".repeat(64), 0)),
                createdAt = 1_000,
            ).isEmpty()
        )

        assertTrue(
            TranslationScaffold.chaptersOf(
                translationArtifactVersionIds = listOf(translationA),
                chapters = emptyList(),
                createdAt = 1_000,
            ).isEmpty()
        )
    }

    @Test
    fun `what is missing is matched on the chapter named not on how many there are`() {
        val chapters = (0..3).map(::sourceChapter)

        // Chapter 1 never landed but chapter 2 did, which a count would read as
        // "the last one is missing" and scaffold the wrong chapter.
        val missing = TranslationScaffold.chaptersMissingFrom(
            translationChapters = listOf(chapters[0], chapters[2]).map {
                translationChapterFor(it, translationA)
            },
            sourceChapters = chapters,
        )

        assertEquals(listOf(chapters[1].id, chapters[3].id), missing.map { it.id })
    }

    @Test
    fun `a translation that is already complete is missing nothing`() {
        val chapters = (0..2).map(::sourceChapter)

        // So that running a catch-up twice proposes nothing the second time,
        // rather than a second copy of every chapter under fresh ids.
        assertEquals(
            emptyList(),
            TranslationScaffold.chaptersMissingFrom(
                translationChapters = chapters.map { translationChapterFor(it, translationA) },
                sourceChapters = chapters,
            )
        )
    }
}
