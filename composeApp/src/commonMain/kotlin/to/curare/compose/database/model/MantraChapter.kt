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
import to.curare.compose.nostr.nip30303.ChapterEvent
import to.curare.compose.nostr.nip30303.tags.ArtifactVersionIdTag
import to.curare.compose.nostr.nip30303.tags.IndexTag
import to.curare.compose.nostr.nip30303.tags.NameTag
import to.curare.compose.nostr.nip30303.tags.OriginalTextTag
import to.curare.compose.nostr.nip30303.tags.WordStatisticsTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30302
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraArtifactVersion::class,
            parentColumns = ["id"],
            childColumns = ["artifactVersionId"],
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
data class MantraChapter(
    @PrimaryKey
    val id: HexKey,
    val artifactVersionId: HexKey,
    val publicKey: HexKey,
    val name: String,
    val originalText: String,
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
    fun toChapterEvent(): ChapterEvent {
        return ChapterEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches ChapterEvent.build so the event id round-trips.
            tags = TagArrayBuilder<ChapterEvent>()
                .addUnique(AltTag.assemble(ChapterEvent.ALT_DESCRIPTION))
                .addUnique(
                    ArtifactVersionIdTag.assemble(artifactVersionId)
                )
                .addUnique(NameTag.assemble(name))
                .addUnique(OriginalTextTag.assemble(originalText))
                .addUnique(
                    IndexTag.assemble(
                        index
                    )
                )
                .addUnique(
                    WordStatisticsTag.assemble(wordCount, characterCount)
                )
                .build(),
            content = "",
            sig = signature
        )
    }

    companion object {
        fun fromChapterEventTemplate(
            chapterEventTemplate: EventTemplate<ChapterEvent>,
            chatRoomId: HexKey,
            userPublicKey: HexKey,
        ): MantraChapter? {
            return fromChapterEvent(
                ChapterEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey,
                        tags = chapterEventTemplate.tags,
                        content = chapterEventTemplate.content,
                        createdAt = chapterEventTemplate.createdAt,
                        kind = chapterEventTemplate.kind,
                    ),
                    content = chapterEventTemplate.content,
                    tags = chapterEventTemplate.tags,
                    createdAt = chapterEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "", // Unsigned rumor
                ),
                chatRoomId = chatRoomId,
            )
        }

        fun fromChapterEvent(chapterEvent: ChapterEvent, chatRoomId: HexKey): MantraChapter? {
            return chapterEvent.name()?.let { name ->
                chapterEvent.originalText()?.let { originalText ->
                    chapterEvent.wordStatisticsTag?.let { wordStatisticsTag ->
                        chapterEvent.artifactVersionId()?.let { artifactId ->
                            chapterEvent.index()?.let { index ->
                                MantraChapter(
                                    id = chapterEvent.id,
                                    publicKey = chapterEvent.pubKey,
                                    createdAt = Instant.fromEpochSeconds(chapterEvent.createdAt),
                                    artifactVersionId = artifactId,
                                    name = name,
                                    originalText = originalText,
                                    index = index,
                                    wordCount = wordStatisticsTag.wordCount,
                                    characterCount = wordStatisticsTag.characterCount,
                                    signature = chapterEvent.sig,
                                    chatRoomId = chatRoomId
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
