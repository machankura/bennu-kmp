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
import com.it.curated.compose.nostr.nip30303.TranslationChunkEvent
import com.it.curated.compose.nostr.nip30303.tags.ChunkIdTag
import com.it.curated.compose.nostr.nip30303.tags.IndexTag
import com.it.curated.compose.nostr.nip30303.tags.TranslationChapterIdTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30311
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraChunk::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MantraTranslationChapter::class,
            parentColumns = ["id"],
            childColumns = ["translationChapterId"],
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
data class MantraTranslationChunkProposal(
    @PrimaryKey
    val id: HexKey,
    val publicKey: HexKey,
    val chunkId: HexKey,
    val translationChapterId: HexKey,
    val text: String,
    val index: Int,

    /**
     * Signature will be an empty string if this is a rumor
     */
    val signature: String,

    val chatRoomId: String,

    val relayHint: String? = null,
    val marmotGroupEventId: HexKey? = null,

    /**
     * Related nostrEventId if this is not a rumor...
     */
    override val nostrEventId: HexKey? = null,
    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt
): OptionalNostrEventEntity, TimestampedEntity {

    fun toTranslationChunkEvent(): TranslationChunkEvent {
        return TranslationChunkEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches TranslationChunkEvent.build so the id round-trips.
            tags = TagArrayBuilder<TranslationChunkEvent>()
                .addUnique(AltTag.assemble(TranslationChunkEvent.ALT_DESCRIPTION))
                .addUnique(
                    TranslationChapterIdTag.assemble(translationChapterId)
                )
                .addUnique(
                    ChunkIdTag.assemble(chunkId)
                )
                .addUnique(
                    IndexTag.assemble(index)
                )
                .build(),
            content = text,
            sig = signature
        )
    }

    companion object {
        fun fromTranslationChunkEventTemplate(
            translationChunkEventTemplate: EventTemplate<TranslationChunkEvent>,
            chatRoomId: HexKey,
            userPublicKey: HexKey,
        ): MantraTranslationChunkProposal? {
            return fromTranslationChunkEvent(
                TranslationChunkEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey,
                        tags = translationChunkEventTemplate.tags,
                        content = translationChunkEventTemplate.content,
                        createdAt = translationChunkEventTemplate.createdAt,
                        kind = translationChunkEventTemplate.kind,
                    ),
                    content = translationChunkEventTemplate.content,
                    tags = translationChunkEventTemplate.tags,
                    createdAt = translationChunkEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "", // Unsigned rumor
                ),
                chatRoomId = chatRoomId,
            )
        }

        fun fromTranslationChunkEvent(translationChunkEvent: TranslationChunkEvent, chatRoomId: HexKey): MantraTranslationChunkProposal? {
            return translationChunkEvent.translationChapterId()?.let { translationChapterId ->
                translationChunkEvent.chunkId()?.let { chunkId ->
                    translationChunkEvent.index()?.let { index ->
                        MantraTranslationChunkProposal(
                            id = translationChunkEvent.id,
                            publicKey = translationChunkEvent.pubKey,
                            createdAt = Instant.fromEpochSeconds(translationChunkEvent.createdAt),
                            translationChapterId = translationChapterId,
                            chunkId = chunkId,
                            index = index,
                            text = translationChunkEvent.content,
                            signature = translationChunkEvent.sig,
                            chatRoomId = chatRoomId
                        )
                    }
                }
            }
        }
    }
}
