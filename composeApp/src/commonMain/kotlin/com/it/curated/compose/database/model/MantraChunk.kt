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
import com.it.curated.compose.nostr.nip30303.ChunkEvent
import com.it.curated.compose.nostr.nip30303.tags.ChapterIdTag
import com.it.curated.compose.nostr.nip30303.tags.IndexTag
import com.it.curated.compose.nostr.nip30303.tags.WordStatisticsTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30303
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraChapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
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
data class MantraChunk(
    @PrimaryKey
    val id: HexKey,
    val chapterId: HexKey,
    val publicKey: HexKey,
    val text: String,
    val index: Int,
    val wordCount: Int,
    val characterCount: Int,

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
    fun toChunkEvent(): ChunkEvent {
        return ChunkEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches ChunkEvent.build so the event id round-trips.
            tags = TagArrayBuilder<ChunkEvent>()
                .addUnique(AltTag.assemble(ChunkEvent.ALT_DESCRIPTION))
                .addUnique(
                    ChapterIdTag.assemble(chapterId)
                )
                .addUnique(
                    IndexTag.assemble(
                        index
                    )
                )
                .addUnique(
                    WordStatisticsTag.assemble(wordCount, characterCount)
                )
                .build(),
            content = text,
            sig = signature
        )
    }

    companion object {
        fun fromChunkEventTemplate(
            chunkEventTemplate: EventTemplate<ChunkEvent>,
            chatRoomId: HexKey,
            userPublicKey: HexKey,
        ): MantraChunk? {
            return fromChunkEvent(
                ChunkEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey,
                        tags = chunkEventTemplate.tags,
                        content = chunkEventTemplate.content,
                        createdAt = chunkEventTemplate.createdAt,
                        kind = chunkEventTemplate.kind,
                    ),
                    content = chunkEventTemplate.content,
                    tags = chunkEventTemplate.tags,
                    createdAt = chunkEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "", // Unsigned rumor
                ),
                chatRoomId = chatRoomId,
            )
        }

        fun fromChunkEvent(chunkEvent: ChunkEvent, chatRoomId: HexKey): MantraChunk? {
            return chunkEvent.wordStatisticsTag?.let { wordStatisticsTag ->
                chunkEvent.index()?.let { index ->
                    chunkEvent.chapterId()?.let { chapterId ->
                        MantraChunk(
                            id = chunkEvent.id,
                            publicKey = chunkEvent.pubKey,
                            createdAt = Instant.fromEpochSeconds(chunkEvent.createdAt),
                            index = index,
                            wordCount = wordStatisticsTag.wordCount,
                            characterCount = wordStatisticsTag.characterCount,
                            chapterId = chapterId,
                            text = chunkEvent.content,
                            signature = chunkEvent.sig,
                            chatRoomId = chatRoomId
                        )
                    }

                }
            }
        }
    }
}
