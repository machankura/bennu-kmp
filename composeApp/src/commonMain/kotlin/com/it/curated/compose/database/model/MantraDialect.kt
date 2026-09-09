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
import com.it.curated.compose.nostr.nip30303.DialectEvent
import com.it.curated.compose.nostr.nip30303.tags.CountryTag
import com.it.curated.compose.nostr.nip30303.tags.LanguageTag
import com.it.curated.compose.nostr.nip30303.tags.NameTag
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 30304
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class MantraDialect(
    @PrimaryKey
    val id: HexKey,
    val publicKey: HexKey,
    val name: String,
    val country: String,
    val language: String,

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
    fun toDialectEvent(): DialectEvent {
        return DialectEvent(
            id = id,
            pubKey = publicKey,
            createdAt = createdAt.epochSeconds,
            // Tag order matches DialectEvent.build so the event id round-trips.
            tags = TagArrayBuilder<DialectEvent>()
                .addUnique(AltTag.assemble(DialectEvent.ALT_DESCRIPTION))
                .addUnique(NameTag.assemble(name))
                .addUnique(CountryTag.assemble(country))
                .addUnique(LanguageTag.assemble(language))
                .build(),
            content = language, // TODO: Consider using the isoCode... as content?
            sig = signature
        )
    }

    companion object {
        fun fromDialectEventTemplate(
            dialectEventTemplate: EventTemplate<DialectEvent>,
            chatRoomId: HexKey,
            userPublicKey: HexKey,
        ): MantraDialect? {
            return fromDialectEvent(
                DialectEvent(
                    id = EventHasher.hashId(
                        pubKey = userPublicKey,
                        tags = dialectEventTemplate.tags,
                        content = dialectEventTemplate.content,
                        createdAt = dialectEventTemplate.createdAt,
                        kind = dialectEventTemplate.kind,
                    ),
                    content = dialectEventTemplate.content,
                    tags = dialectEventTemplate.tags,
                    createdAt = dialectEventTemplate.createdAt,
                    pubKey = userPublicKey,
                    sig = "", // Unsigned rumor
                ),
                chatRoomId = chatRoomId,
            )
        }

        fun fromDialectEvent(dialectEvent: DialectEvent, chatRoomId: HexKey): MantraDialect? {
            return dialectEvent.name()?.let { name ->
                dialectEvent.language()?.let { language ->
                    dialectEvent.country()?.let { country ->

                        MantraDialect(
                            id = dialectEvent.id,
                            publicKey = dialectEvent.pubKey,
                            createdAt = Instant.fromEpochSeconds(dialectEvent.createdAt),
                            name = name,
                            language = language,
                            country = country,
                            signature = dialectEvent.sig,
                            chatRoomId = chatRoomId
                        )
                    }
                }
            }
        }
    }
}
