package com.it.curated.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils
import com.it.curated.compose.nostr.nip30303.tags.ArtifactVersionIdTag
import com.it.curated.compose.nostr.nip30303.tags.IndexTag
import com.it.curated.compose.nostr.nip30303.tags.NameTag
import com.it.curated.compose.nostr.nip30303.tags.OriginalTextTag
import com.it.curated.compose.nostr.nip30303.tags.WordStatisticsTag


@Immutable
class ChapterEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {
    val wordStatisticsTag = tags.firstNotNullOfOrNull(WordStatisticsTag::parse)

    fun artifactVersionIdReference() = tags.firstNotNullOfOrNull(ArtifactVersionIdTag::parse)?.ref
    fun artifactVersionId() = artifactVersionIdReference()?.eventId

    fun name() = tags.firstNotNullOfOrNull(NameTag::parse)?.name

    /**
     * The markdown the chapter is, whole.
     *
     * The chapter carries its text even though the chunks cut from it are
     * signed alongside it and carry the same words between them. It is the
     * chapter as the group signed it: chunk boundaries are a decision about how
     * to divide the work, and a chapter that kept only the pieces could never be
     * divided again differently without losing what was agreed to.
     */
    fun originalText() = tags.firstNotNullOfOrNull(OriginalTextTag::parse)?.originalText

    fun index() = tags.firstNotNullOfOrNull(IndexTag::parse)?.index
    fun wordCount() = wordStatisticsTag?.wordCount
    fun characterCount() = wordStatisticsTag?.characterCount

    companion object {
        const val KIND = 30302
        const val ALT_DESCRIPTION = "Chapter"

        fun build(
            artifactVersionId: String,
            name: String,
            originalText: String,
            index: Int,
            wordCount: Int,
            characterCount: Int,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChapterEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(ArtifactVersionIdTag.assemble(artifactVersionId))
            addUnique(NameTag.assemble(name))
            addUnique(OriginalTextTag.assemble(originalText))
            addUnique(IndexTag.assemble(index))
            addUnique(WordStatisticsTag.assemble(wordCount, characterCount))
            initializer()
        }
    }
}
