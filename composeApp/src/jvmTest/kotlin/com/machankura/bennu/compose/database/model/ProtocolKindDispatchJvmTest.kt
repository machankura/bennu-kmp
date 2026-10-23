package com.machankura.bennu.compose.database.model

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.nostr.dkg.DkgRitualEvents
import com.machankura.bennu.compose.nostr.frost.FrostSigningEvents

/**
 * A key ceremony's or a signing session's message, arriving in a Marmot room,
 * is recognised as one before anything that shares its kind number.
 *
 * 30310-30312 are both ceremony kinds and nip30303 document kinds. Round 1 of a
 * subgroup's ceremony (30312, hex content) was read as a document submission,
 * failed to parse, and reached the parent's chat as a bubble of raw hex on every
 * other admin's device -- docs/ceremony-creation.md, finding F11.
 */
class ProtocolKindDispatchJvmTest {

    private val database: BennuDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())

    @AfterTest
    fun closeDb() = database.close()

    private val self = KeyPair()
    private val sender = KeyPair().pubKey.toHexKey()
    private val chatRoomId = "639f".repeat(16)

    private var next = 0

    /** A kind:445 this device has stored, carrying one inner event of [kind]. */
    private suspend fun arrive(kind: Kind, content: String): Pair<GroupEvent, ChatMessage?> {
        val n = next++
        val groupEventId = "%064x".format(1000 + n)

        database.nostrEventDao().upsert(
            NostrEvent(
                id = groupEventId,
                pubKey = sender,
                kind = GroupEvent.KIND,
                tags = arrayOf(arrayOf("h", chatRoomId)),
                content = "ciphertext",
                sig = "0".repeat(128),
            )
        )
        database.marmotGroupEventDao().upsert(
            MarmotGroupEvent(
                id = groupEventId,
                userPublicKey = self.pubKey.toHexKey(),
                publicKey = sender,
                chatRoomId = chatRoomId,
                signature = "0".repeat(128),
                encryptedContent = "ciphertext",
                expiresAt = null,
                createdAt = Instant.fromEpochSeconds(1),
            )
        )

        val groupEvent = GroupEvent(
            id = groupEventId,
            pubKey = sender,
            createdAt = 1,
            tags = arrayOf(arrayOf("h", chatRoomId)),
            content = "ciphertext",
            sig = "0".repeat(128),
        )
        val inner = Event(
            id = "%064x".format(2000 + n),
            pubKey = sender,
            createdAt = 1,
            kind = kind,
            tags = DkgRitualEvents.assembleTags(sessionId = "5e55".repeat(16)),
            content = content,
            sig = "",
        )

        val line = ChatMessage.fromGroupEventResult(
            database = database,
            activeKeyPair = self,
            groupEvent = groupEvent,
            groupEventResult = GroupEventResult.ApplicationMessage(
                groupId = chatRoomId,
                innerEventJson = inner.toJson(),
                senderLeafIndex = 1,
                epoch = 1,
            ),
            senderIdentity = sender,
        )

        return groupEvent to line
    }

    // What a round-1 message actually carries: a ChillDKG pmsg1, hex encoded.
    // Its leading zero is what made it unparseable as an event.
    private val round1Content = "024f99efa68cafafd766eec95ad940cd096a5f1a7b42d14132121ad7d2dc310d03"

    @Test
    fun `a ceremony message writes no chat line, whatever shares its number`(): Unit = runBlocking {
        DkgRitualEvents.ALL.forEach { kind ->
            val (groupEvent, line) = arrive(kind, content = round1Content)

            assertNull(line, "kind $kind is the ceremony's, and writes no line of its own")
            assertNull(
                database.chatMessageDao().getChatMessagesByMarmotGroupEventId(groupEvent.id),
                "and nothing is filed in its place",
            )
        }

        assertEquals(
            DkgRitualEvents.ALL,
            database.marmotInnerEventDao()
                .getByChatRoomAndKinds(chatRoomId, DkgRitualEvents.ALL.toList())
                .map { it.kind }
                .toSet(),
            "every one is still stored, so a ceremony can replay what arrived before its proposal",
        )
    }

    @Test
    fun `a signing message writes no chat line`(): Unit = runBlocking {
        FrostSigningEvents.ALL.forEach { kind ->
            val (_, line) = arrive(kind, content = "{}")
            assertNull(line, "kind $kind is a signing session's")
        }

        assertTrue(
            database.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId).isEmpty(),
            "and the room's transcript is untouched",
        )
    }
}
