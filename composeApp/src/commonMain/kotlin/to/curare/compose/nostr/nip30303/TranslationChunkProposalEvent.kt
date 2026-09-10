package to.curare.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import to.curare.compose.nostr.nip30303.tags.ChunkIdTag
import to.curare.compose.nostr.nip30303.tags.IndexTag
import to.curare.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag
import to.curare.compose.nostr.nip30303.tags.TranslationChapterIdTag


@Immutable
class TranslationChunkProposalEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    fun translationChapterIdReference() = tags.firstNotNullOfOrNull(TranslationChapterIdTag::parse)?.ref
    fun translationChapterId() = translationChapterIdReference()?.eventId

    fun index() = tags.firstNotNullOfOrNull(IndexTag::parse)?.index

    fun chunkIdReference() = tags.firstNotNullOfOrNull(ChunkIdTag::parse)?.ref
    fun chunkId() = chunkIdReference()?.eventId

    companion object {
        const val KIND = 30309
        const val ALT_DESCRIPTION = "TranslationChunk"

        fun build(
            translationChapterId: String,
            chunkId: String,
            index: Int,
            text: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<TranslationChunkProposalEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, text, createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(TranslationChapterIdTag.assemble(translationChapterId))
            addUnique(ChunkIdTag.assemble(chunkId))
            addUnique(IndexTag.assemble(index))
            initializer()
        }
    }
}
