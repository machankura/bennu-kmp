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
import to.curare.compose.nostr.nip30303.tags.ArtifactVersionMetadataTag
import to.curare.compose.nostr.nip30303.tags.DialectIdTag
import to.curare.compose.nostr.nip30303.tags.LicenseTag
import to.curare.compose.nostr.nip30303.tags.UrlTag
import to.curare.compose.nostr.nip30303.tags.VisibilityTag


@Immutable
class ArtifactEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {
    fun url() = tags.firstNotNullOfOrNull(UrlTag::parse)?.url
    fun visibility() = tags.firstNotNullOfOrNull(VisibilityTag::parse)?.visibility
    fun license() = tags.firstNotNullOfOrNull(LicenseTag::parse)?.license

    fun dialectIdReference() = tags.firstNotNullOfOrNull(DialectIdTag::parse)?.ref
    fun dialectId() = dialectIdReference()?.eventId

    /**
     * The label of the version this artifact starts life with.
     *
     * Carried on the artifact rather than in an event of its own because the
     * group signs the artifact and nothing else. A first version proposed
     * separately would need either a second quorum for one form or an id each
     * device invents for itself, and invented ids differ on every device holding
     * the same artifact. Null on artifacts written before this was declared.
     */
    fun versionLabel() = tags.firstNotNullOfOrNull(ArtifactVersionMetadataTag::parse)?.versionLabel

    companion object {
        const val KIND = 30300
        const val ALT_DESCRIPTION = "Artifact"

        fun build(
            name: String,
            url: String,
            visibility: String,
            license: String,
            dialectId: String,
            versionLabel: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ArtifactEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, name, createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(UrlTag.assemble(url))
            addUnique(VisibilityTag.assemble(visibility))
            addUnique(LicenseTag.assemble(license))
            addUnique(
                DialectIdTag.assemble(dialectId)
            )
            addUnique(ArtifactVersionMetadataTag.assemble(versionLabel))
            initializer()
        }
    }
}
