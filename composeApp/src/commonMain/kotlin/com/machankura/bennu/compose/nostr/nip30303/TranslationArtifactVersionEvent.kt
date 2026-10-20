package com.machankura.bennu.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils
import com.machankura.bennu.compose.nostr.nip30303.tags.ArtifactVersionIdTag
import com.machankura.bennu.compose.nostr.nip30303.tags.DialectIdTag
import com.machankura.bennu.compose.nostr.nip30303.tags.LicenseTag
import com.machankura.bennu.compose.nostr.nip30303.tags.NameTag
import com.machankura.bennu.compose.nostr.nip30303.tags.VisibilityTag


@Immutable
class TranslationArtifactVersionEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    fun name() = tags.firstNotNullOfOrNull(NameTag::parse)?.name
    fun visibility() = tags.firstNotNullOfOrNull(VisibilityTag::parse)?.visibility
    fun license() = tags.firstNotNullOfOrNull(LicenseTag::parse)?.license

    fun artifactVersionIdReference() = tags.firstNotNullOfOrNull(ArtifactVersionIdTag::parse)?.ref
    fun artifactVersionId() = artifactVersionIdReference()?.eventId

    fun dialectIdReference() = tags.firstNotNullOfOrNull(DialectIdTag::parse)?.ref
    fun dialectId() = dialectIdReference()?.eventId

    companion object {
        const val KIND = 30306
        const val ALT_DESCRIPTION = "TranslationArtifactVersion"

        fun build(
            artifactVersionId: String,
            dialectId: String,
            name: String,
            visibility: String,
            license: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<TranslationArtifactVersionEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, "", createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(ArtifactVersionIdTag.assemble(artifactVersionId))
            addUnique(DialectIdTag.assemble(dialectId))
            addUnique(NameTag.assemble(name))
            addUnique(VisibilityTag.assemble(visibility))
            addUnique(LicenseTag.assemble(license))
            initializer()
        }
    }
}
