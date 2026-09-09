package com.it.curated.compose.nostr.nip30303

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
import com.it.curated.compose.nostr.nip30303.tags.ArtifactIdTag
import com.it.curated.compose.nostr.nip30303.tags.ArtifactVersionMetadataTag


@Immutable
class ArtifactVersionEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    fun versionLabel() = tags.firstNotNullOfOrNull(ArtifactVersionMetadataTag::parse)?.versionLabel

    fun artifactIdReference() = tags.firstNotNullOfOrNull(ArtifactIdTag::parse)?.ref
    fun artifactId() = artifactIdReference()?.eventId

    companion object {
        const val KIND = 30301
        const val ALT_DESCRIPTION = "ArtifactVersion"

        /**
         * The first version [artifact] declares, ready to be signed with it.
         *
         * An artifact and the version it starts life with go to the group as one
         * batch, so this is built from the artifact *after* it has been authored
         * under the group's key -- [artifact] is the unsigned event the session
         * will sign, which is where the id this carries comes from. Building it
         * anywhere else would mean naming an artifact id before one exists.
         *
         * It takes the artifact's own timestamp, so the batch reads as one act
         * rather than two events that happen to share a session.
         *
         * This used to be derived on arrival instead, from the label the
         * artifact carries -- the same shape a chapter's chunks were in, and
         * abandoned for the same reason. Deriving cost nothing while the
         * alternative was a second quorum, and nothing is what it bought: a row
         * naming the group as its author with no signature to show for it, which
         * a chapter then hangs off. A batch is one quorum, so the version can
         * carry the group's signature over its own label.
         *
         * Empty when the artifact declares no version -- every artifact written
         * before it did -- which leaves a batch of one and an artifact with no
         * version, exactly as before.
         */
        fun initialVersionOf(artifact: Event): List<EventTemplate<ArtifactVersionEvent>> {
            val versionLabel = ArtifactEvent(
                id = artifact.id,
                pubKey = artifact.pubKey,
                createdAt = artifact.createdAt,
                tags = artifact.tags,
                content = artifact.content,
                sig = artifact.sig
            ).versionLabel() ?: return emptyList()

            return listOf(
                build(content = versionLabel, createdAt = artifact.createdAt) {
                    addUnique(ArtifactIdTag.assemble(artifact.id))
                }
            )
        }

        fun build(
            content: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ArtifactVersionEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, content, createdAt) {
            alt(ALT_DESCRIPTION)
            initializer()
        }
    }
}
