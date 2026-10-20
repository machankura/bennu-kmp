package com.machankura.bennu.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.machankura.bennu.compose.nostr.nip30303.tags.ChapterIdTag
import com.machankura.bennu.compose.nostr.nip30303.tags.IndexTag
import com.machankura.bennu.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag


@Immutable
class TranslationChapterEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    fun translationArtifactVersionIdReference() = tags.firstNotNullOfOrNull(TranslationArtifactVersionIdTag::parse)?.ref
    fun translationArtifactVersionId() = translationArtifactVersionIdReference()?.eventId

    fun chapterIdReference() = tags.firstNotNullOfOrNull(ChapterIdTag::parse)?.ref
    fun chapterId() = chapterIdReference()?.eventId

    fun index() = tags.firstNotNullOfOrNull(IndexTag::parse)?.index

    companion object {
        const val KIND = 30308
        const val ALT_DESCRIPTION = "TranslationChapter"

        fun build(
            translationArtifactVersionId: String,
            chapterId: String,
            index: Int,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<TranslationChapterEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(TranslationArtifactVersionIdTag.assemble(translationArtifactVersionId))
            addUnique(ChapterIdTag.assemble(chapterId))
            addUnique(IndexTag.assemble(index))
            initializer()
        }
    }
}
