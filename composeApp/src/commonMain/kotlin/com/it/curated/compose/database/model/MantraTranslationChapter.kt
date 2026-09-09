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
import com.it.curated.compose.nostr.nip30303.TranslationChapterEvent
import com.it.curated.compose.nostr.nip30303.tags.ChapterIdTag
import com.it.curated.compose.nostr.nip30303.tags.IndexTag
import com.it.curated.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30308
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraTranslationArtifactVersion::class,
            parentColumns = ["id"],
            childColumns = ["translationArtifactVersionId"],
            onDelete = ForeignKey.CASCADE,
        ),
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
data class MantraTranslationChapter(
    @PrimaryKey
    val id: HexKey,
    val publicKey: HexKey,
    val chapterId: HexKey,
    val translationArtifactVersionId: HexKey,
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
    fun toTranslationChapterEvent(): TranslationChapterEvent {
        return TranslationChapterEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches TranslationChapterEvent.build so the id round-trips.
            tags = TagArrayBuilder<TranslationChapterEvent>()
                .addUnique(AltTag.assemble(TranslationChapterEvent.ALT_DESCRIPTION))
                .addUnique(
                    TranslationArtifactVersionIdTag.assemble(translationArtifactVersionId)
                )
                .addUnique(
                    ChapterIdTag.assemble(chapterId)
                )
                .addUnique(
                    IndexTag.assemble(index)
                )
                .build(),
            content = "",
            sig = signature
        )
    }

    companion object {
        fun fromTranslationChapterEventTemplate(
            translationChapterEventTemplate: EventTemplate<TranslationChapterEvent>,
            chatRoomId: HexKey,
            userPublicKey: HexKey,
        ): MantraTranslationChapter? {
            return fromTranslationChapterEvent(
                TranslationChapterEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey,
                        tags = translationChapterEventTemplate.tags,
                        content = translationChapterEventTemplate.content,
                        createdAt = translationChapterEventTemplate.createdAt,
                        kind = translationChapterEventTemplate.kind,
                    ),
                    content = translationChapterEventTemplate.content,
                    tags = translationChapterEventTemplate.tags,
                    createdAt = translationChapterEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "", // Unsigned rumor
                ),
                chatRoomId = chatRoomId,
            )
        }

        fun fromTranslationChapterEvent(translationChapterEvent: TranslationChapterEvent, chatRoomId: HexKey): MantraTranslationChapter? {
            return translationChapterEvent.chapterId()?.let { chapterId ->
                translationChapterEvent.translationArtifactVersionId()?.let { translationArtifactVersionId ->
                    translationChapterEvent.index()?.let { index ->
                        MantraTranslationChapter(
                            id = translationChapterEvent.id,
                            publicKey = translationChapterEvent.pubKey,
                            createdAt = Instant.fromEpochSeconds(translationChapterEvent.createdAt),
                            chapterId = chapterId,
                            translationArtifactVersionId = translationArtifactVersionId,
                            index = index,
                            signature = translationChapterEvent.sig,
                            chatRoomId = chatRoomId
                        )
                    }
                }
            }
        }
    }
}

