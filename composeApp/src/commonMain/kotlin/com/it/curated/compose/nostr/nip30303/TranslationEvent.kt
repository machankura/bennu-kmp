package com.it.curated.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import com.it.curated.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag
import com.it.curated.compose.nostr.nip30303.tags.TranslationChunkIdTag


@Immutable
class TranslationEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    fun translationChunkIdReference() = tags.firstNotNullOfOrNull(TranslationChunkIdTag::parse)?.ref
    fun translationChunkId() = translationChunkIdReference()?.eventId

    fun translationArtifactVersionIdReference() = tags.firstNotNullOfOrNull(TranslationArtifactVersionIdTag::parse)?.ref
    fun translationArtifactVersionId() = translationArtifactVersionIdReference()?.eventId

    companion object {
        const val KIND = 30311
        const val ALT_DESCRIPTION = "Translation"

        fun build(
            content: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ArtifactEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, content, createdAt) {
            alt(ALT_DESCRIPTION)
            initializer()
        }
    }
}
