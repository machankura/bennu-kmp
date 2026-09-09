package com.it.curated.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip31Alts.alt
import com.it.curated.compose.database.model.traits.OptionalNostrEventEntity
import com.it.curated.compose.database.model.traits.TimestampedEntity
import com.it.curated.compose.nostr.nip30303.TranslationContributorListEvent
import com.it.curated.compose.nostr.nip30303.tags.ContributorTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30312
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = MantraTranslation::class,
            parentColumns = ["id"],
            childColumns = ["translationId"],
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
data class MantraTranslationContributor(
    @PrimaryKey
    val id: HexKey,
    val dTag: String,
    val publicKey: HexKey,
    val translationId: HexKey,
    val translatorPublicKey: HexKey,

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
        fun toTranslationChapterProofReaderListEvent(mantraTranslationContributors: List<MantraTranslationContributor>): TranslationContributorListEvent? {
            mantraTranslationContributors.firstOrNull()?.let { firstMantraTranslationTranslator ->
                val allFromSameEvent = mantraTranslationContributors.all {
                    it.publicKey == firstMantraTranslationTranslator.publicKey
                            && it.createdAt == firstMantraTranslationTranslator.createdAt
                            && it.dTag == firstMantraTranslationTranslator.dTag
                }

                if (allFromSameEvent) {
                    val translatorPublicKeys = mantraTranslationContributors.map {
                        ContributorTag.assemble(
                            pubKey = it.publicKey,
                            role = ContributorTag.ROLE_TRANSLATOR
                        )
                    }
                    val template = eventTemplate<TranslationContributorListEvent>(
                        kind = TranslationContributorListEvent.KIND,
                        description = "",
                        createdAt = firstMantraTranslationTranslator.createdAt.epochSeconds
                    ) {
                        addUnique(
                            DTag.assemble(
                                firstMantraTranslationTranslator.dTag
                            )
                        )
                        alt(TranslationContributorListEvent.ALT_DESCRIPTION)

                    }


                    return TranslationContributorListEvent(
                        id = EventHasher.hashId(
                            pubKey = firstMantraTranslationTranslator.publicKey,
                            tags = template.tags,
                            createdAt = template.createdAt,
                            content = template.content,
                            kind = template.kind
                        ),
                        pubKey = firstMantraTranslationTranslator.publicKey,
                        createdAt = firstMantraTranslationTranslator.createdAt.epochSeconds,
                        tags = translatorPublicKeys.toTypedArray(),
                        content = template.content,
                        sig = firstMantraTranslationTranslator.signature
                    )
                }
            }
            return null
        }
    }
}

