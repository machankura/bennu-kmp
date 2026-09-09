package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Transaction
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.it.curated.compose.database.CuratedDatabase
import com.it.curated.compose.database.model.ChatMessage
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.MantraDialect
import com.it.curated.compose.database.model.MarmotInnerEvent
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.nostr.nip30303.ArtifactVersionEvent
import com.it.curated.compose.nostr.nip30303.DialectEvent
import com.it.curated.compose.nostr.nip30303.SubmissionEvent
import com.it.curated.compose.nostr.nip30303.tags.ArtifactIdTag
import com.it.curated.compose.repository.MantraRepository.Companion.DEFAULT_LICENSE
import com.it.curated.compose.repository.MantraRepository.Companion.DEFAULT_VISIBILITY
import kotlin.time.Instant

@Dao
abstract class MantraDao(
    private val database: CuratedDatabase,
) {
    val logger = Logger.withTag("NostrDao")

    /**
     * The unsigned nip30303 event [template] describes, authored by [publicKey].
     *
     * Its id is computed the same way the matching Mantra* entity computes
     * its own, so the row on disk and the payload on the wire are the same
     * event rather than two copies of one.
     */
    private fun rumorOf(
        template: EventTemplate<out Event>,
        publicKey: HexKey,
    ): Event = Event(
        id = EventHasher.hashId(
            pubKey = publicKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
        ),
        pubKey = publicKey,
        createdAt = template.createdAt,
        kind = template.kind,
        tags = template.tags,
        content = template.content,
        // A rumor. What signs for this reaching the group is the kind:445 the
        // outbound pipeline wraps it in, not the payload itself.
        sig = "",
    )

    /**
     * Queue [payload] for the group inside a [SubmissionEvent] that
     * [submitterPublicKey] authors.
     *
     * nip30303 events are never sent on their own. Wrapping them means the
     * payload keeps whoever wrote it as its author while the group still knows
     * which member put it there -- see [SubmissionEvent] for why the two are
     * worth telling apart.
     *
     * The submission is stored as an unprocessed rumor (null marmotGroupEventId),
     * which is what the outbound pipeline picks up and encrypts into a kind:445
     * group event for the chat room.
     */
    private suspend fun submitToGroup(
        chatRoomId: String,
        submitterPublicKey: HexKey,
        payload: Event,
        text: String,
    ): MarmotInnerEvent {
        val submissionTemplate = SubmissionEvent.build(payload = payload)

        val submissionInnerEvent = MarmotInnerEvent(
            id = EventHasher.hashId(
                pubKey = submitterPublicKey,
                createdAt = submissionTemplate.createdAt,
                kind = submissionTemplate.kind,
                tags = submissionTemplate.tags,
                content = submissionTemplate.content,
            ),
            publicKey = submitterPublicKey,
            kind = SubmissionEvent.KIND,
            createdAt = Instant.fromEpochSeconds(submissionTemplate.createdAt),
            tags = submissionTemplate.tags,
            content = submissionTemplate.content,
            payloadEventId = payload.id,
            chatRoomId = chatRoomId,
        )

        sendMarmotInnerEvent(
            chatRoomId = chatRoomId,
            userPublicKey = submitterPublicKey,
            text = text,
            marmotInnerEvent = submissionInnerEvent,
        )

        return submissionInnerEvent
    }

    @Transaction
    open suspend fun addDialect(
        localChatRoom: LocalChatRoom,
        name: String,
        country: String,
        language: String,
        userPublicKey: HexKey,
    ): MantraDialect? {
        logger.d("addDialect: $name")

        val dialectEventTemplate = DialectEvent.build(
            name = name,
            country = country,
            language = language,
        )

        val mantraDialect = MantraDialect.fromDialectEventTemplate(
            dialectEventTemplate = dialectEventTemplate,
            chatRoomId = localChatRoom.chatRoom.id,
            userPublicKey = userPublicKey,
        ) ?: return null

        return try {
            database.mantraDialectDao().upsert(mantraDialect)

            submitToGroup(
                chatRoomId = mantraDialect.chatRoomId,
                submitterPublicKey = userPublicKey,
                payload = rumorOf(dialectEventTemplate, userPublicKey),
                text = "Added $name as a dialect",
            )

            mantraDialect
        } catch (error: Throwable) {
            logger.e("Failed to add dialect \"$name\" to chat room ${localChatRoom.chatRoom.id}", error)
            null
        }

    }

    @Transaction
    open suspend fun addArtifactVersion(
        localChatRoom: LocalChatRoom,
        artifactId: HexKey,
        versionLabel: String,
        userPublicKey: HexKey,
    ): MantraArtifactVersion? {
        return addArtifactVersionInternal(
            localChatRoom = localChatRoom,
            artifactId = artifactId,
            versionLabel = versionLabel,
            userPublicKey = userPublicKey
        )
    }

    private suspend fun addArtifactVersionInternal(
        localChatRoom: LocalChatRoom,
        artifactId: HexKey,
        versionLabel: String,
        userPublicKey: HexKey,
    ): MantraArtifactVersion? {
        // The version label is carried in the event content (see
        // MantraArtifactVersion.fromArtifactVersionEvent).
        val artifactVersionEventTemplate = ArtifactVersionEvent.build(
            content = versionLabel,
        ) {
            addUnique(ArtifactIdTag.assemble(artifactId))
        }

        val mantraArtifactVersion = MantraArtifactVersion.fromArtifactVersionEventTemplate(
            artifactVersionEventTemplate = artifactVersionEventTemplate,
            chatRoomId = localChatRoom.chatRoom.id,
            userPublicKey = userPublicKey,
        ) ?: return null

        database.mantraArtifactVersionDao().upsert(mantraArtifactVersion)

        submitToGroup(
            chatRoomId = mantraArtifactVersion.chatRoomId,
            submitterPublicKey = userPublicKey,
            payload = rumorOf(artifactVersionEventTemplate, userPublicKey),
            text = "Add the ${artifactVersionEventTemplate.content} version",
        )

        return mantraArtifactVersion
    }

    private suspend fun sendMarmotInnerEvent(
        chatRoomId: String,
        userPublicKey: HexKey,
        text: String,
        marmotInnerEvent: MarmotInnerEvent
    ) {
        database.marmotInnerEventDao().upsert(
            marmotInnerEvent
        )

        database.chatMessageDao().upsert(
            ChatMessage(
                content = text,
                chatRoomId = chatRoomId,
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = marmotInnerEvent.id,
            )
        )
        // TODO: Depending on visibility (if signature is non-empty) might want to queue this event for broadcasting...
    }
}