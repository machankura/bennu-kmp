package to.curare.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip31Alts.AltTag
import to.curare.compose.database.model.traits.OptionalNostrEventEntity
import to.curare.compose.database.model.traits.TimestampedEntity
import to.curare.compose.nostr.nip30303.TranslationChunkEvent
import to.curare.compose.nostr.nip30303.tags.ChunkIdTag
import to.curare.compose.nostr.nip30303.tags.IndexTag
import to.curare.compose.nostr.nip30303.tags.TranslationChapterIdTag
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
data class MantraTranslationChunk(
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
        ): MantraTranslationChunk? {
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

        fun fromTranslationChunkEvent(translationChunkEvent: TranslationChunkEvent, chatRoomId: HexKey): MantraTranslationChunk? {
            return translationChunkEvent.translationChapterId()?.let { translationChapterId ->
                translationChunkEvent.chunkId()?.let { chunkId ->
                    translationChunkEvent.index()?.let { index ->
                        MantraTranslationChunk(
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
