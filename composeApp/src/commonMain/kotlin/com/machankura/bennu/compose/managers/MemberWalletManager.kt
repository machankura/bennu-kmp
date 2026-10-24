package com.machankura.bennu.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.nostr.wallet.GroupKeyNoticeEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A member's guarded wallet, from the asking to the guarding:
 * `docs/member-guarded-wallets.md`.
 *
 * The member's device makes a random key, works out every ancestor leaf, stores
 * the key and only then asks the group. A signer puts the request to the group,
 * the group's quorum signs a certificate saying it guards that key for that
 * person, and the certificate is what every device opens the wallet on.
 */
object MemberWalletManager {
    private const val TAG = "MemberWalletManager"

    private val logger = Logger.withTag(TAG)

    /**
     * The key [chatRoomId] signs with and the certificate it was born with --
     * what a member's grant is worked out from -- or null when this device knows
     * neither.
     *
     * This device's own verified key state first. Behind it, the newest answer to
     * a key notice in the room that checks out: a member welcomed after the
     * founding holds no key state, and asks (`askForGroupKey`). Never filed as
     * key state -- see [GroupKeyNoticeEvent].
     */
    suspend fun groupKeyFor(database: BennuDatabase, chatRoomId: String): GroupKeyNoticeEvent.GroupKey? {
        GroupKeyStateManager.keyStateFor(database, chatRoomId)?.takeIf { it.verifies() }?.let { state ->
            return GroupKeyNoticeEvent.GroupKey(
                chatRoomId = state.chatRoomId,
                thresholdPublicKey = state.thresholdPublicKey,
                certificate = state.birthCertificateJson?.let { Event.fromJsonOrNull(it) },
            )
        }

        return noticesIn(database, chatRoomId)
            .sortedByDescending { it.createdAt }
            .firstNotNullOfOrNull { GroupKeyNoticeEvent.read(it, chatRoomId) }
    }

    /**
     * Asks [chatRoomId] for its key, as [userPublicKey]. Returns false, asking
     * nothing, when this device already knows it or has asked and not been
     * answered: a second question would get the same answer.
     */
    suspend fun askForGroupKey(database: BennuDatabase, chatRoomId: String, userPublicKey: HexKey): Boolean {
        if (groupKeyFor(database, chatRoomId) != null) return false

        val notices = noticesIn(database, chatRoomId)
        val answered = notices.mapNotNull { GroupKeyNoticeEvent.answers(it) }.toSet()
        if (notices.any { GroupKeyNoticeEvent.isAsking(it) && it.pubKey == userPublicKey && it.id !in answered }) return false

        queue(database, chatRoomId, userPublicKey, GroupKeyNoticeEvent.askingTags(chatRoomId), content = "")
        logger.i("Asked $chatRoomId for its key")
        return true
    }

    /**
     * Answers [request], an asking notice that arrived in [chatRoomId], as
     * [userPublicKey] -- when this device holds the room's verified key state and
     * nobody has answered it yet. Returns whether it answered.
     *
     * Any member holding the state may answer, and none is elected to: a
     * duplicate answer costs bandwidth and nothing else, since every answer is
     * checked on its own and they all say the same thing. The chronicle's
     * request is answered the same way.
     */
    suspend fun answerKeyRequest(database: BennuDatabase, chatRoomId: String, request: Event, userPublicKey: HexKey): Boolean {
        if (!GroupKeyNoticeEvent.isAsking(request) || request.pubKey == userPublicKey) return false
        val state = GroupKeyStateManager.keyStateFor(database, chatRoomId)?.takeIf { it.verifies() } ?: return false
        val path = state.pathIndices() ?: return false
        if (noticesIn(database, chatRoomId).any { GroupKeyNoticeEvent.answers(it) == request.id }) return false

        val groupKey = GroupKeyNoticeEvent.GroupKey(
            chatRoomId = state.chatRoomId,
            thresholdPublicKey = state.thresholdPublicKey,
            certificate = state.birthCertificateJson?.let { Event.fromJsonOrNull(it) },
        )
        queue(database, chatRoomId, userPublicKey, GroupKeyNoticeEvent.answeringTags(request.id, groupKey, path), content = state.thresholdPublicKey)
        logger.i("Answered ${request.pubKey.take(8)}'s request for $chatRoomId's key")
        return true
    }

    private suspend fun noticesIn(database: BennuDatabase, chatRoomId: String): List<Event> =
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(GroupKeyNoticeEvent.KIND))
            .map { it.asEvent() }

    /**
     * A stored inner event as the event it was, unsigned: an inner event's
     * authority is the MLS frame it arrived in, not a signature, so `sig` is
     * empty here as it is everywhere inner events are read back.
     */
    internal fun MarmotInnerEvent.asEvent(): Event =
        Event(id = id, pubKey = publicKey, createdAt = createdAt.epochSeconds, kind = kind, tags = tags, content = content, sig = "")

    /**
     * Queues one inner event in [chatRoomId] for the outbound pipeline, the way
     * every inner event is: a row, which `encryptAndSendMarmotInnerEvent` sends.
     */
    internal suspend fun queue(
        database: BennuDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        tags: Array<Array<String>>,
        content: String,
        kind: Int = GroupKeyNoticeEvent.KIND,
        createdAt: Instant = Clock.System.now(),
    ): HexKey {
        val id = EventHasher.hashId(userPublicKey, createdAt.epochSeconds, kind, tags, content)
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id,
                publicKey = userPublicKey,
                kind = kind,
                createdAt = createdAt,
                tags = tags,
                content = content,
                chatRoomId = chatRoomId,
            )
        )
        return id
    }
}
