package to.curare.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import to.curare.compose.nostr.nip30303.tags.ChapterIdTag
import to.curare.compose.nostr.nip30303.tags.IndexTag
import to.curare.compose.nostr.nip30303.tags.WordStatisticsTag
import to.curare.compose.text.Markdown


@Immutable
class ChunkEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {
    val wordStatisticsTag = tags.firstNotNullOfOrNull(WordStatisticsTag::parse)

    fun index() = tags.firstNotNullOfOrNull(IndexTag::parse)?.index
    fun wordCount() = wordStatisticsTag?.wordCount
    fun characterCount() = wordStatisticsTag?.characterCount

    fun chapterIdReference() = tags.firstNotNullOfOrNull(ChapterIdTag::parse)?.ref
    fun chapterId() = chapterIdReference()?.eventId

    companion object {
        const val KIND = 30303
        const val ALT_DESCRIPTION = "Chunk"

        /**
         * The chunks [chapter]'s text splits into, ready to be signed with it.
         *
         * A chapter and its chunks go to the group as one batch, so these are
         * built from the chapter *after* it has been authored under the group's
         * key -- [chapter] is the unsigned event the session will sign, which is
         * where the id each chunk carries comes from. Splitting anywhere else
         * would mean naming a chapter id before one exists.
         *
         * They take the chapter's own timestamp, so the batch reads as one act
         * rather than as events that happen to share a session.
         *
         * Empty when the chapter has no text to split, which is a chapter with
         * nothing in it to translate.
         */
        fun splitOf(chapter: Event): List<EventTemplate<ChunkEvent>> {
            val originalText = ChapterEvent(
                id = chapter.id,
                pubKey = chapter.pubKey,
                createdAt = chapter.createdAt,
                tags = chapter.tags,
                content = chapter.content,
                sig = chapter.sig
            ).originalText() ?: return emptyList()

            return Markdown.splitParagraphs(originalText).mapIndexed { index, paragraph ->
                build(
                    chapterId = chapter.id,
                    text = paragraph,
                    index = index,
                    wordCount = Markdown.wordCount(paragraph),
                    characterCount = Markdown.characterCount(paragraph),
                    createdAt = chapter.createdAt,
                )
            }
        }

        fun build(
            chapterId: String,
            text: String,
            index: Int,
            wordCount: Int,
            characterCount: Int,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChunkEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, text, createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(ChapterIdTag.assemble(chapterId))
            addUnique(IndexTag.assemble(index))
            addUnique(WordStatisticsTag.assemble(wordCount, characterCount))
            initializer()
        }
    }
}
