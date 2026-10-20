package com.machankura.bennu.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nipF4Podcasts.metadata.tags.AuthorTag
import com.vitorpamplona.quartz.utils.TimeUtils
import com.machankura.bennu.compose.database.model.traits.OptionalNostrEventEntity
import com.machankura.bennu.compose.database.model.traits.TimestampedEntity
import com.machankura.bennu.compose.nostr.nip30303.TranslationChapterContributorListEvent
import com.machankura.bennu.compose.nostr.nip30303.tags.ContributorTag
import com.machankura.bennu.compose.nostr.nip30303.tags.TranslationChapterIdTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30310
 */
@Entity(
    foreignKeys = [
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
data class MantraTranslationChapterContributor(
    @PrimaryKey
    val id: HexKey,
    val publicKey: HexKey,
    val translationChapterId: HexKey,
    val translatorPublicKey: HexKey,

    val role: String,
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

    companion object {
        fun toTranslationChapterTranslatorListEvent(mantraTranslationChapterTranslators: List<MantraTranslationChapterContributor>): TranslationChapterContributorListEvent {
            // TODO: create TranslationChapterProofReaderListEvent from mantraTranslationChapterProofReaders
            return TranslationChapterContributorListEvent(
                id = "",
                pubKey = "",
                createdAt = TimeUtils.now(),
                tags = TagArrayBuilder<TranslationChapterContributorListEvent>()
                    .addUnique(
                        TranslationChapterIdTag.assemble("")
                    )
                    .addUnique(
                        ContributorTag.assemble("", ContributorTag.ROLE_TRANSLATOR)
                    )
                    .build(),
                content = "",
                sig = ""
            )
        }
    }
}

