package com.it.curated.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.runBlocking
import com.it.curated.compose.database.CuratedDatabase
import com.it.curated.compose.database.builder.getRoomDatabase
import com.it.curated.compose.database.model.ChatRoom
import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.NostrEvent
import com.it.curated.compose.database.model.Profile
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.nostr.nip30303.DialectEvent
import com.it.curated.compose.nostr.nip30303.SubmissionEvent
import com.it.curated.compose.repository.MantraRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The nip30303 create-entity flow: every `add*` on [MantraDao] writes the entity and queues a
 * [SubmissionEvent] carrying the same event for the group, in one transaction.
 *
 * The invariant worth a test is the one `rumorOf` exists for. The entity's id is computed by
 * the `Mantra*.from*EventTemplate` factory and the payload's id is computed here, from the same
 * template -- so the row on disk and the payload on the wire are meant to be *the same event*,
 * not two copies of one. Nothing enforces that: both sides compile independently, both produce
 * a plausible 64-character id, and a divergence would only show up as a group that can never
 * match an arriving submission to the entity it was supposed to create.
 */
class MantraDaoJvmTest {

    private val db: CuratedDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<CuratedDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val author = "a".repeat(64)
    private val roomId = "b".repeat(64)

    /** ChatRoom -> Profile -> NostrEvent, the foreign key chain a room hangs off. */
    private suspend fun seedRoom(): LocalChatRoom {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = author,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(Profile(publicKey = author, userName = "author", nostrEventId = nostrEventId))
        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = author,
            subject = "a translation room",
            description = null,
            mlsGroupState = null,
        )
        db.chatRoomDao().upsert(chatRoom)
        return LocalChatRoom(chatRoom = chatRoom)
    }

    private suspend fun submissions() = db.marmotInnerEventDao()
        .getByChatRoomAndKinds(roomId, listOf(SubmissionEvent.KIND))

    private suspend fun addDialect(name: String = "Sesotho") = db.mantraDao().addDialect(
        localChatRoom = seedRoom(),
        name = name,
        country = "ZA",
        language = "st",
        userPublicKey = author,
    )

    @Test
    fun `a dialect is stored and queued for the group in one call`() = runBlocking {
        val dialect = assertNotNull(addDialect(), "addDialect returned null")

        assertEquals("Sesotho", dialect.name)
        assertEquals(roomId, dialect.chatRoomId)
        assertEquals(author, dialect.publicKey)
        assertEquals(1, submissions().size, "the dialect was stored without being submitted")
    }

    /**
     * The `rumorOf` invariant, asserted across the seam: the submission records the payload's
     * id, and that id has to be the entity's own. If the two factories ever compute it
     * differently the group receives a submission whose payload matches nothing on disk.
     */
    @Test
    fun `the stored dialect and the submitted payload are the same event`() = runBlocking {
        val dialect = assertNotNull(addDialect())

        val submission = submissions().single()

        assertEquals(
            dialect.id,
            submission.payloadEventId,
            "the entity id and the submitted payload id have diverged",
        )
    }

    /**
     * The envelope is not the payload. A submission's own id is the SubmissionEvent's, which is
     * why `deleteByPayloadEventId` exists -- a superseded nip30303 event cannot be un-queued by
     * its own id.
     */
    @Test
    fun `the submission is an envelope with its own id`() = runBlocking {
        val dialect = assertNotNull(addDialect())

        val submission = submissions().single()

        assertEquals(SubmissionEvent.KIND, submission.kind)
        assertTrue(
            submission.id != dialect.id,
            "the envelope must not share the payload's id, or it could not be told apart",
        )
        assertEquals(author, submission.publicKey)
        assertEquals(roomId, submission.chatRoomId)
    }

    /**
     * `marmotGroupEventId == null` is what makes the row unprocessed, which is the state the
     * outbound pipeline selects on to encrypt it into a kind:445. Filed as processed, it would
     * be stored and never sent, and the group would never learn about the dialect.
     */
    @Test
    fun `the submission is queued unprocessed for the outbound pipeline`() = runBlocking {
        addDialect()

        val submission = submissions().single()

        assertNull(submission.marmotGroupEventId, "a queued submission must not look processed")
    }

    /** The room's feed reads ChatMessage, so an added entity has to leave a line behind. */
    @Test
    fun `a chat message line is written so the room shows the change`() = runBlocking {
        addDialect(name = "isiZulu")

        val submission = submissions().single()
        val chatMessage = db.chatMessageDao().getChatMessagesByMarmotInnerEventId(submission.id)

        assertNotNull(chatMessage, "no chat line was written for the submission")
        assertEquals("Added isiZulu as a dialect", chatMessage.content)
        assertEquals(roomId, chatMessage.chatRoomId)
        assertTrue(chatMessage.isUserMessage)
    }

    /**
     * A second entity type through the same path, because the store-and-submit shape is the
     * convention every `add*` follows rather than something `addDialect` does on its own.
     */
    @Test
    fun `an artifact version follows the same store-and-submit shape`() = runBlocking {
        val localChatRoom = seedRoom()
        val dialect = assertNotNull(
            db.mantraDao().addDialect(
                localChatRoom = localChatRoom,
                name = "Setswana",
                country = "ZA",
                language = "tn",
                userPublicKey = author,
            )
        )
        val artifactId = "d".repeat(64)
        db.mantraArtifactDao().upsert(
            MantraArtifact(
                id = artifactId,
                publicKey = author,
                name = "a text",
                url = "https://example.invalid/text",
                visibility = MantraRepository.DEFAULT_VISIBILITY,
                dialectId = dialect.id,
                license = MantraRepository.DEFAULT_LICENSE,
                chatRoomId = roomId,
                signature = "",
            )
        )

        val version = assertNotNull(
            db.mantraDao().addArtifactVersion(
                localChatRoom = localChatRoom,
                artifactId = artifactId,
                versionLabel = "first draft",
                userPublicKey = author,
            ),
            "addArtifactVersion returned null",
        )

        val versionSubmission = assertNotNull(
            submissions().singleOrNull { it.payloadEventId == version.id },
            "the artifact version was stored without a matching submission",
        )
        assertEquals(SubmissionEvent.KIND, versionSubmission.kind)
        assertNull(versionSubmission.marmotGroupEventId)
        assertEquals(2, submissions().size, "the dialect and the version should each be queued")
    }
}
