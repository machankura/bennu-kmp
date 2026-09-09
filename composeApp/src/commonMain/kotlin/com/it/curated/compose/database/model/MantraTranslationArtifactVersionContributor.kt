package com.it.curated.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.utils.TimeUtils
import com.it.curated.compose.database.model.traits.OptionalNostrEventEntity
import com.it.curated.compose.database.model.traits.TimestampedEntity
import com.it.curated.compose.nostr.nip30303.TranslationArtifactVersionContributorListEvent
import com.it.curated.compose.nostr.nip30303.tags.ContributorTag
import com.it.curated.compose.nostr.nip30303.tags.TranslationArtifactVersionIdTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30307
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraTranslationArtifactVersionContributor::class,
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
data class MantraTranslationArtifactVersionContributor(
    @PrimaryKey
    val id: HexKey,
    val publicKey: HexKey,
    val translationArtifactVersionId: HexKey,
    val editorPublicKey: HexKey,

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
        fun toTranslationChapterProofReaderListEvent(chatRoomId: HexKey, mantraTranslationChapterProofReaders: List<MantraTranslationArtifactVersionContributor>): TranslationArtifactVersionContributorListEvent {
            // TODO: create TranslationChapterProofReaderListEvent from mantraTranslationChapterProofReaders
            return TranslationArtifactVersionContributorListEvent(
                id = "",
                pubKey = "",
                createdAt = TimeUtils.now(),
                tags = TagArrayBuilder<TranslationArtifactVersionContributorListEvent>()

                    .addUnique(
                        TranslationArtifactVersionIdTag.assemble("") // TODO: use actual value..
                    )
                    // TODO: Add tag for editorPublicKey
                    .addUnique(
                        ContributorTag.assemble(
                            "",
                            ContributorTag.ROLE_EDITOR
                        )
                    )
                    .build(),
                content = "",
                sig = ""
            )
        }
    }
}

