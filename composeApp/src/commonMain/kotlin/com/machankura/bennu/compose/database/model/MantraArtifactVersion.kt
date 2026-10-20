package com.machankura.bennu.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip31Alts.AltTag
import com.machankura.bennu.compose.database.model.traits.OptionalNostrEventEntity
import com.machankura.bennu.compose.database.model.traits.TimestampedEntity
import com.machankura.bennu.compose.nostr.nip30303.ArtifactVersionEvent
import com.machankura.bennu.compose.nostr.nip30303.tags.ArtifactIdTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30301
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraArtifact::class,
            parentColumns = ["id"],
            childColumns = ["artifactId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MantraArtifactVersion(
    @PrimaryKey
    val id: HexKey,
    val artifactId: HexKey,
    val publicKey: HexKey,

    /**
     * Version Tag...
     */
    val versionLabel: String,

    /**
     * Signature will be an empty string if this is a rumor
     */
    val signature: String,

    val chatRoomId: String,

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
    @Deprecated(
        "Chronicle fallback for work signed before the GroupSignedEvent table. Read the " +
            "event off that table instead; this rebuild goes when the last pre-v13 install " +
            "does -- see the removal checklist in docs/member-chronicle.md."
    )
    fun toArtifactVersionEvent(): ArtifactVersionEvent {
        return ArtifactVersionEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches ArtifactVersionEvent.initialVersionOf, which puts
            // the alt first because `build` does, or the event id does not
            // round-trip. It was the other way round here for as long as nothing
            // called this -- see ChronicleRoundTripTest, which is what calls it now.
            tags = TagArrayBuilder<ArtifactVersionEvent>()
                .addUnique(
                    AltTag.assemble(ArtifactVersionEvent.ALT_DESCRIPTION)
                )
                .addUnique(
                    ArtifactIdTag.assemble(artifactId)
                )
                .build(),
            content = versionLabel,
            sig = signature
        )
    }

    companion object {
        fun fromArtifactVersionEventTemplate(
            artifactVersionEventTemplate: EventTemplate<ArtifactVersionEvent>,
            chatRoomId: HexKey,
            userPublicKey: HexKey,
        ): MantraArtifactVersion? {
            return fromArtifactVersionEvent(
                ArtifactVersionEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey,
                        tags = artifactVersionEventTemplate.tags,
                        content = artifactVersionEventTemplate.content,
                        createdAt = artifactVersionEventTemplate.createdAt,
                        kind = artifactVersionEventTemplate.kind,
                    ),
                    content = artifactVersionEventTemplate.content,
                    tags = artifactVersionEventTemplate.tags,
                    createdAt = artifactVersionEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "", // Unsigned rumor
                ),
                chatRoomId = chatRoomId,
            )
        }

        fun fromArtifactVersionEvent(artifactVersionEvent: ArtifactVersionEvent, chatRoomId: HexKey, ): MantraArtifactVersion? {
            return artifactVersionEvent.artifactId()?.let { artifactId ->
                MantraArtifactVersion(
                    id = artifactVersionEvent.id,
                    publicKey = artifactVersionEvent.pubKey,
                    createdAt = Instant.fromEpochSeconds(artifactVersionEvent.createdAt),
                    artifactId = artifactId,
                    versionLabel = artifactVersionEvent.content,
                    signature = artifactVersionEvent.sig,
                    chatRoomId = chatRoomId
                )
            }
        }
    }
}
