package com.it.curated.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip31Alts.AltTag
import com.it.curated.compose.database.model.traits.OptionalNostrEventEntity
import com.it.curated.compose.database.model.traits.TimestampedEntity
import com.it.curated.compose.nostr.nip30303.ArtifactEvent
import com.it.curated.compose.nostr.nip30303.tags.ArtifactVersionMetadataTag
import com.it.curated.compose.nostr.nip30303.tags.DialectIdTag
import com.it.curated.compose.nostr.nip30303.tags.LicenseTag
import com.it.curated.compose.nostr.nip30303.tags.UrlTag
import com.it.curated.compose.nostr.nip30303.tags.VisibilityTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30300
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraDialect::class,
            parentColumns = ["id"],
            childColumns = ["dialectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class MantraArtifact(
    @PrimaryKey
    val id: HexKey,
    val publicKey: String,
    val name: String,
    val url: String, // Hash this to get the dTag...
    val visibility: String, // public, private, permissioned (public, but only members can contribute)
    val dialectId: String,
    val license: String,
//    val artifactApprovalId: String,

    val chatRoomId: String,

    /**
     * Signature will be an empty string if this is a rumor
     */
    val signature: String,

    val relayHint: String? = null,

    val marmotGroupEventId: HexKey? = null,

    /** The [GroupSignedEvent] this row was made from, when the group signed it. */
    val groupSignedEventId: HexKey? = null,
    /**
     * Related nostrEventId if this is not a rumor...
      */
    override val nostrEventId: HexKey? = null,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt
): OptionalNostrEventEntity, TimestampedEntity {
    /**
     * The event the group signed, rebuilt from this row.
     *
     * [versionLabel] is a parameter because it is not on this row and cannot be.
     * `fromArtifactEvent` reads the artifact's own fields and drops the version
     * metadata, which `ChatMessage.applyInnerEvent` has by then turned into the
     * artifact's first `MantraArtifactVersion`. It was still part of what the
     * group put its signature to, so a rebuild without it hashes to a different
     * id and produces an event the signature does not cover -- which a receiver
     * reads as a forgery, silently. Recover it from the initial version: the one
     * whose `createdAt` is this artifact's, since `initialVersionOf` derives it
     * from the same event.
     *
     * Tag order matches [ArtifactEvent.build] exactly -- alt first -- for the
     * same reason, and `ChronicleRoundTripTest` is what says so. Both of those were
     * wrong here until a chronicle needed to rebuild an artifact and nothing had
     * ever called this.
     */
    @Deprecated(
        "Chronicle fallback for work signed before the GroupSignedEvent table. Read the " +
            "event off that table instead; this rebuild goes when the last pre-v13 install " +
            "does -- see the removal checklist in docs/member-chronicle.md."
    )
    fun toArtifactEvent(versionLabel: String): ArtifactEvent {
        return ArtifactEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches ArtifactEvent.build so the event id round-trips.
            tags = TagArrayBuilder<ArtifactEvent>()
                .addUnique(
                    AltTag.assemble(ArtifactEvent.ALT_DESCRIPTION)
                )
                .addUnique(UrlTag.assemble(url))
                .addUnique(VisibilityTag.assemble(visibility))
                .addUnique(LicenseTag.assemble(license))
                .addUnique(
                    DialectIdTag.assemble(dialectId)
                )
                .addUnique(
                    ArtifactVersionMetadataTag.assemble(versionLabel)
                )
                .build(),
            content = name,
            sig = signature
        )
    }

    companion object {
        fun fromArtifactEventTemplate(artifactEventTemplate: EventTemplate<ArtifactEvent>, chatRoomId: HexKey, userPublicKey: HexKey): MantraArtifact? {
            return fromArtifactEvent(
                ArtifactEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey, // TODO: get pub key...
                        tags = artifactEventTemplate.tags,
                        content = artifactEventTemplate.content,
                        createdAt = artifactEventTemplate.createdAt,
                        kind = artifactEventTemplate.kind
                    ),
                    content = artifactEventTemplate.content,
                    tags = artifactEventTemplate.tags,
                    createdAt = artifactEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "" // Unsigned....
                ),
                chatRoomId = chatRoomId
            )
        }
        fun fromArtifactEvent(artifactEvent: ArtifactEvent, chatRoomId: HexKey, ): MantraArtifact? {
            return artifactEvent.url()?.let { url ->
                artifactEvent.visibility()?.let { visibility ->
                    artifactEvent.license()?.let { license ->
                        artifactEvent.dialectId()?.let { dialectId ->
                            MantraArtifact(
                                id = artifactEvent.id,
                                publicKey = artifactEvent.pubKey,
                                dialectId = dialectId,
                                createdAt = Instant.fromEpochSeconds(artifactEvent.createdAt),
                                url = url,
                                license = license,
                                visibility = visibility,
                                name = artifactEvent.content,
                                signature = artifactEvent.sig,
                                chatRoomId = chatRoomId
                            )
                        }
                    }
                }
            }
        }
    }
}
