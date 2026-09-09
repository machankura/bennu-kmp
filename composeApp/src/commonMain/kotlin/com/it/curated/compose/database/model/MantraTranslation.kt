package com.it.curated.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.utils.ensure
import com.it.curated.compose.database.model.traits.OptionalNostrEventEntity
import com.it.curated.compose.database.model.traits.TimestampedEntity
import com.it.curated.compose.nostr.nip30303.TranslationEvent
import com.it.curated.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag
import com.it.curated.compose.nostr.nip30303.tags.TranslationChunkIdTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30305
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraTranslationChunk::class,
            parentColumns = ["id"],
            childColumns = ["translationChunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MantraTranslationArtifactVersion::class,
            parentColumns = ["id"],
            childColumns = ["translationArtifactVersionId"],
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
data class MantraTranslation(
    @PrimaryKey
    val id: HexKey,
    val publicKey: HexKey,
    val translationChunkId: HexKey,
    val translationArtifactVersionId: HexKey,
    val text: String,

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

    fun toTranslationEvent(): TranslationEvent {
        return TranslationEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            tags = TagArrayBuilder<TranslationEvent>()
                .addUnique(
                    TranslationArtifactVersionIdTag.assemble(translationArtifactVersionId)
                )
                .addUnique(
                    TranslationChunkIdTag.assemble(translationChunkId)
                )
                .build(),
            content = text,
            sig = signature
        )
    }

    companion object {
        fun fromTranslationEvent(translationEvent: TranslationEvent, chatRoomId: HexKey): MantraTranslation? {
            ensure(translationEvent.translationChunkId() != null) { return null}
            ensure(translationEvent.translationArtifactVersionId() != null) { return null}

            return translationEvent.translationChunkId()?.let { translationChunkId ->
                translationEvent.translationArtifactVersionId()?.let { translationArtifactVersionId ->
                    MantraTranslation(
                        id = translationEvent.id,
                        publicKey = translationEvent.pubKey,
                        createdAt = Instant.fromEpochSeconds(translationEvent.createdAt),
                        text = translationEvent.content,
                        translationChunkId = translationChunkId,
                        translationArtifactVersionId = translationArtifactVersionId,
                        signature = translationEvent.sig,
                        chatRoomId = chatRoomId
                    )
                }
            }
        }
    }
}