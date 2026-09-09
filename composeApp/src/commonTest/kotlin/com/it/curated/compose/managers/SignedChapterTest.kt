package com.it.curated.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.crypto.frost.TweakCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.it.curated.compose.database.model.FrostSigningItem
import com.it.curated.compose.database.model.MantraChapter
import com.it.curated.compose.database.model.MantraChunk
import com.it.curated.compose.extensions.toHex
import com.it.curated.compose.nostr.nip30303.ChapterEvent
import com.it.curated.compose.nostr.nip30303.ChunkEvent

/**
 * A chapter and its chunks the group signed, from proposal to rows, against
 * real FROST.
 *
 * Adding a chapter used to write the rows and submit them, one submission for
 * the chapter and one per paragraph; the rows said the submitter wrote them,
 * because they had. Now the group signs the lot in one session, and the claim
 * this test exists to hold is that every row a device ends up with is the
 * group's: authored by the threshold key, carrying a signature that verifies
 * over its own id, with the chunks naming the chapter that was signed beside
 * them.
 *
 * None of that is visible when it breaks. A row whose author is the proposer
 * looks exactly like a row whose author is the group -- both are opaque hex --
 * and a chunk that names the wrong chapter is a foreign key away from vanishing
 * with no sign of why.
 *
 * A batch is k independent FROST instances, so the signatures here are made one
 * per item, each over its own message with its own nonces, which is the only
 * way they can be made.
 */
class SignedChapterTest {
    private val participants = 3
    private val threshold = 2

    /** The member who pasted the text. Nothing they own should end up on the rows. */
    private val proposer = "9".repeat(64)
    private val artifactVersionId = "b".repeat(64)

    private val originalText = "The first paragraph.\n\nThe second paragraph."

    /** Stands in for a completed ceremony; the test is about what gets signed, not the DKG. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /**
     * The room the group signs in: derived from its key at the admin path, which
     * is what makes the room's id and the key it signs as one value.
     */
    private val room: SharedKeyDerivation.Derived = SharedKeyDerivation.derive(
        thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
        path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    )

    private val tweakCache: TweakCache = room.cache

    /** The group's nostr identity here, which is also [chatRoomId]. */
    private val groupPubKey = room.hex

    private val chatRoomId = room.hex

    private fun proposalTemplate(
        name: String = "Chapter 1",
        text: String = originalText,
        index: Int = 0,
    ) = ChapterEvent.build(
        artifactVersionId = artifactVersionId,
        name = name,
        originalText = text,
        index = index,
        wordCount = 6,
        characterCount = text.length,
        createdAt = 1_700_000_000L,
    )

    /**
     * Exactly what `FrostSigningManager.unsignedEventOf` does, and it must stay
     * exactly that: the proposer's fields re-authored under the group's key.
     */
    private fun unsignedEventOf(template: com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<*>) = Event(
        id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content
        ),
        pubKey = groupPubKey,
        createdAt = template.createdAt,
        kind = template.kind,
        tags = template.tags,
        content = template.content,
        sig = ""
    )

    private fun itemsOver(unsignedEvents: List<Event>) = unsignedEvents.mapIndexed { index, event ->
        FrostSigningItem(
            sessionId = "s".repeat(64),
            itemIndex = index,
            unsignedEventJson = event.toJson(),
            eventId = event.id,
            // One seed per item, never shared: two messages under one nonce is
            // how a share is extracted.
            nonceRandom = "f".repeat(63) + "${index + 1}"
        )
    }

    /** A quorum signing one item's event, in the manager's order. */
    private fun groupSignature(item: FrostSigningItem): String {
        val message = ByteVector(item.eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32(
                    "a".repeat(62) + "${item.itemIndex + 1}${signerId + 1}"
                ),
                secretShare = keyMaterial.secretShares[signerId],
                publicShare = keyMaterial.publicShares[signerId],
                tweakedThresholdPublicKey = tweakCache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val signingSession = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { keyMaterial.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = tweakCache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            signingSession.sign(
                nonces[position].first,
                keyMaterial.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }

        return signingSession.aggregateSigs(partials).right!!.toByteArray().toHex()
    }

    /**
     * The batch a proposal makes: the chapter, then a chunk per paragraph, cut
     * from the chapter after it is authored under the group's key -- exactly
     * what `AddChapterViewModel` hands `proposeSigningBatch` as lead and
     * dependents.
     */
    private fun proposedBatch(
        name: String = "Chapter 1",
        text: String = originalText,
        index: Int = 0,
    ): List<Event> {
        val chapter = unsignedEventOf(proposalTemplate(name, text, index))

        return listOf(chapter) + ChunkEvent.splitOf(chapter).map(::unsignedEventOf)
    }

    /** Everything from the form to the rows a device holds afterwards. */
    private fun signedBatch(
        name: String = "Chapter 1",
        text: String = originalText,
        index: Int = 0,
    ): List<Event> = itemsOver(proposedBatch(name, text, index)).map { item ->
        FrostSigningManager.signedEvent(item, groupSignature(item))
    }

    private fun signedChapterEvent(
        name: String = "Chapter 1",
        text: String = originalText,
        index: Int = 0,
    ): ChapterEvent = signedBatch(name, text, index).first().let { signed ->
        ChapterEvent(
            signed.id, signed.pubKey, signed.createdAt, signed.tags, signed.content, signed.sig
        )
    }

    private fun signedChunkEvents(
        text: String = originalText,
    ): List<ChunkEvent> = signedBatch(text = text).drop(1).map { signed ->
        ChunkEvent(
            signed.id, signed.pubKey, signed.createdAt, signed.tags, signed.content, signed.sig
        )
    }

    @Test
    fun `the chapter the group signs is authored by the group, not the proposer`() {
        val chapter = MantraChapter.fromChapterEvent(signedChapterEvent(), chatRoomId)

        assertNotNull(chapter)
        assertEquals(groupPubKey, chapter.publicKey)
        assertNotEquals(proposer, chapter.publicKey)
    }

    @Test
    fun `the group it is authored by is the room it was signed in`() {
        // Signing runs at the room's derivation path, so the author is the room's
        // own id rather than the bare threshold key. That is what lets anybody
        // holding the row check it without being told which key to expect -- and
        // it is why the path a session signs at cannot come from the proposer.
        val chapter = MantraChapter.fromChapterEvent(signedChapterEvent(), chatRoomId)

        assertEquals(chatRoomId, chapter?.publicKey)
        assertNotEquals(
            keyMaterial.thresholdPublicKey.xOnly().value.toHex(),
            chapter?.publicKey,
            "a chapter must be signed by the room's key, not by the group's root key"
        )
    }

    @Test
    fun `the row's id is the id the group put its signature to`() {
        // Every device builds this row from the same signed event, so the id has
        // to be the one that was signed rather than anything recomputed from the
        // proposer. Otherwise members converge on nothing and each holds its own
        // copy of what is meant to be one chapter.
        val item = itemsOver(proposedBatch()).first()
        val signed = FrostSigningManager.signedEvent(item, groupSignature(item))

        val chapter = MantraChapter.fromChapterEvent(
            ChapterEvent(signed.id, signed.pubKey, signed.createdAt, signed.tags, signed.content, signed.sig),
            chatRoomId
        )

        assertEquals(item.eventId, chapter?.id)
    }

    @Test
    fun `the signature on the row verifies against the row's own id and author`() {
        // The payoff of signing rather than submitting: the row carries proof the
        // group made it, checkable by anybody holding it.
        val chapter = MantraChapter.fromChapterEvent(signedChapterEvent(), chatRoomId)

        assertNotNull(chapter)
        assertTrue(
            Nip01Crypto.verify(
                signature = chapter.signature.hexToByteArray(),
                hash = chapter.id.hexToByteArray(),
                pubKey = chapter.publicKey.hexToByteArray()
            ),
            "a chapter row should carry a signature the group's key made over its own id"
        )
    }

    @Test
    fun `what the form asked for is what the group signed`() {
        // The fields travel as tags through a session that knows nothing about
        // chapters. Anything dropped in there is signed away silently -- and the
        // original text most of all, since the chunks are split out of it.
        val chapter = MantraChapter.fromChapterEvent(
            signedChapterEvent(name = "In Detention", index = 2),
            chatRoomId
        )

        assertEquals("In Detention", chapter?.name)
        assertEquals(originalText, chapter?.originalText)
        assertEquals(artifactVersionId, chapter?.artifactVersionId)
        assertEquals(2, chapter?.index)
    }

    @Test
    fun `the chapter is signed with a chunk for every paragraph`() {
        // One session, one approval, and a row per paragraph at the end of it.
        // A translation is of a chunk rather than of a chapter, so a chapter
        // that arrived without them could not be worked on.
        val batch = signedBatch()

        assertEquals(3, batch.size)
        assertEquals(ChapterEvent.KIND, batch.first().kind)
        assertTrue(batch.drop(1).all { it.kind == ChunkEvent.KIND })
    }

    @Test
    fun `every chunk the group signed names the chapter it signed beside it`() {
        // The batch is applied in item order, so the chapter row is written
        // first and the chunks hang off it. A chunk naming anything else fails a
        // foreign key and disappears with no sign of why.
        val batch = signedBatch()
        val chapter = MantraChapter.fromChapterEvent(
            batch.first().let {
                ChapterEvent(it.id, it.pubKey, it.createdAt, it.tags, it.content, it.sig)
            },
            chatRoomId
        )

        val chunks = signedChunkEvents().map { MantraChunk.fromChunkEvent(it, chatRoomId) }

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it?.chapterId == chapter?.id })
        assertEquals(listOf("The first paragraph.", "The second paragraph."), chunks.map { it?.text })
        assertEquals(listOf(0, 1), chunks.map { it?.index })
    }

    @Test
    fun `each chunk row carries a signature the group made over its own id`() {
        // The point of signing the chunks rather than splitting them out of the
        // chapter on arrival: a chunk can be checked on its own, by anybody
        // holding it, without re-deriving it from anything.
        signedChunkEvents().forEach { signed ->
            val chunk = MantraChunk.fromChunkEvent(signed, chatRoomId)

            assertNotNull(chunk)
            assertEquals(groupPubKey, chunk.publicKey)
            assertNotEquals(proposer, chunk.publicKey)
            assertTrue(
                Nip01Crypto.verify(
                    signature = chunk.signature.hexToByteArray(),
                    hash = chunk.id.hexToByteArray(),
                    pubKey = chunk.publicKey.hexToByteArray()
                ),
                "a chunk row should carry a signature the group's key made over its own id"
            )
        }
    }

    @Test
    fun `the chapter and its chunks are different messages to sign`() {
        // A batch is k FROST instances precisely because it has to be: one nonce
        // over two messages leaks the share. That the items are distinct
        // messages is the premise -- `FrostSigningRoundTest` and
        // `SignedGroupKeyStateTest` hold the manager to giving each its own
        // nonce; what a chapter has to hold up is that its own items are not
        // secretly one message repeated.
        val batch = proposedBatch(text = "Again.\n\nAgain.")

        assertEquals(batch.size, batch.map { it.id }.toSet().size)
    }
}
