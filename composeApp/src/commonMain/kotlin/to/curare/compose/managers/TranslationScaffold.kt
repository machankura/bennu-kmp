package to.curare.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import to.curare.compose.database.model.MantraChapter
import to.curare.compose.database.model.MantraTranslationChapter
import to.curare.compose.nostr.nip30303.TranslationChapterEvent

/**
 * The rows that join a translation to the chapters it is a translation of.
 *
 * A translation chapter carries no words. It is `(translation, chapter,
 * position)` and nothing else, and it exists so that a translated chunk has
 * somewhere to hang: a translation without one for a chapter is a translation
 * that chapter cannot be worked on in.
 *
 * Both ends of that join arrive on their own schedule -- a chapter is signed
 * into an artifact that already has translations, a translation is started on
 * an artifact that already has chapters -- so the same scaffolding is built
 * from either side and, when the two miss each other, afterwards. One function
 * for all three, because three copies of a cross product is three chances to
 * disagree about what a translation covers.
 */
object TranslationScaffold {

    /**
     * A source chapter, reduced to what a translation chapter needs of it:
     * which chapter, and where it sits.
     *
     * Taken as this rather than as a [MantraChapter] because the chapter is not
     * always a row yet. When a chapter is signed into an artifact, the
     * scaffolding goes out in the same breath as the chapter itself, against an
     * id that exists only as the unsigned event a session is about to sign.
     */
    data class SourceChapter(
        val id: HexKey,
        val index: Int,
    ) {
        companion object {
            fun of(chapter: MantraChapter) = SourceChapter(id = chapter.id, index = chapter.index)
        }
    }

    /**
     * A translation chapter for every pair of [translationArtifactVersionIds]
     * and [chapters], in that nesting: a translation's chapters stay together
     * and in reading order.
     *
     * The order is the order a session will sign them in and the order they
     * will be applied in, so it is fixed here rather than left to a caller.
     *
     * [createdAt] is stamped on all of them rather than read per event, so that
     * a scaffolding proposed as one act reads as one -- the timestamp of the
     * chapter or the translation that occasioned it, not of whichever moment
     * each template happened to be built in.
     */
    fun chaptersOf(
        translationArtifactVersionIds: List<HexKey>,
        chapters: List<SourceChapter>,
        createdAt: Long,
    ): List<EventTemplate<TranslationChapterEvent>> =
        translationArtifactVersionIds.flatMap { translationArtifactVersionId ->
            chapters.map { chapter ->
                TranslationChapterEvent.build(
                    translationArtifactVersionId = translationArtifactVersionId,
                    chapterId = chapter.id,
                    // The source chapter's own position, never this list's: a
                    // catch-up proposes a subset, so counting the list would
                    // renumber the chapters it happens to be filling in.
                    index = chapter.index,
                    createdAt = createdAt,
                )
            }
        }

    /**
     * The source chapters a translation has nothing for yet.
     *
     * Matched on the source chapter each translation chapter names rather than
     * on how many there are of each. A translation that is missing its second
     * chapter and has picked up its third is not a translation that is missing
     * its last one, and a count would say it was.
     */
    fun chaptersMissingFrom(
        translationChapters: List<MantraTranslationChapter>,
        sourceChapters: List<MantraChapter>,
    ): List<MantraChapter> {
        val covered = translationChapters.mapTo(mutableSetOf()) { it.chapterId }

        return sourceChapters.filterNot { it.id in covered }
    }
}
