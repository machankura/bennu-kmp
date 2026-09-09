package com.it.curated.compose.nostr.chronicle

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.MantraChapter
import com.it.curated.compose.database.model.MantraChunk
import com.it.curated.compose.database.model.MantraDialect
import com.it.curated.compose.database.model.MantraTranslationArtifactVersion
import com.it.curated.compose.database.model.MantraTranslationChapter
import com.it.curated.compose.database.model.MantraTranslationChunk
import com.it.curated.compose.extensions.toHex
import com.it.curated.compose.managers.SharedKeyDerivation
import com.it.curated.compose.nostr.frost.GroupKeyStateEvent
import com.it.curated.compose.nostr.nip30303.ArtifactEvent
import com.it.curated.compose.nostr.nip30303.ArtifactVersionEvent
import com.it.curated.compose.nostr.nip30303.ChapterEvent
import com.it.curated.compose.nostr.nip30303.ChunkEvent
import com.it.curated.compose.nostr.nip30303.DialectEvent
import com.it.curated.compose.nostr.nip30303.TranslationArtifactVersionEvent
import com.it.curated.compose.nostr.nip30303.TranslationChapterEvent
import com.it.curated.compose.nostr.nip30303.TranslationChunkEvent

/**
 * The assumption the chronicle's *fallback* rests on: a row can be turned back
 * into the event the group signed.
 *
 * Signed events used to be stored only as rows. `FrostSigningManager.complete`
 * applied one and what survived was a `Mantra*` row, so a chronicle had to
 * rebuild each payload with `toXEvent()` and hope it came out byte-identical --
 * and if it did not, the id changed, the signature no longer covered it, and
 * every receiver dropped the payload as a forgery, silently, one kind at a time.
 *
 * That is what the round-trip note on each `toXEvent` claims and what nothing
 * asserted until this ran. Every kind in `ChronicleEvent.CHRONICLABLE_KINDS` is
 * checked here against a real FROST quorum, because a signature that verifies is
 * the only evidence that the rebuild is faithful -- comparing fields would only
 * prove the test and the code agree about which fields matter.
 *
 * ### Deprecated, along with what it guards
 *
 * `GroupSignedEvent` keeps the events now, and `ChronicleManager.assemble` reads
 * them; the rebuild survives only for work signed before that table existed, and
 * so does this. Nothing else covers the `toXEvent()` methods, so **this whole
 * file goes when they do** -- see "Retiring the rebuild" in
 * docs/member-chronicle.md. Until then a kind that fails here cannot be chronicled
 * *from a row*, whatever the allowlist says, which for a pre-v13 room is the
 * same thing as not at all.
 */
class ChronicleRoundTripTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony; nothing here is about the DKG. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val room: SharedKeyDerivation.Derived = SharedKeyDerivation.derive(
        thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
        path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    )

    private val tweakCache: TweakCache = room.cache

    /** The group's identity, which is also the room's id. */
    private val chatRoomId = room.hex

    private val dialectId = "b".repeat(64)
    private val artifactVersionId = "c".repeat(64)
    private val chapterId = "d".repeat(64)
    private val translationArtifactVersionId = "e".repeat(64)
    private val translationChapterId = "f".repeat(64)
    private val chunkId = "a".repeat(64)

    /** Exactly what `FrostSigningManager.unsignedEventOf` does. */
    private fun unsignedEventOf(template: EventTemplate<*>) = Event(
        id = EventHasher.hashId(
            pubKey = chatRoomId,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content
        ),
        pubKey = chatRoomId,
        createdAt = template.createdAt,
        kind = template.kind,
        tags = template.tags,
        content = template.content,
        sig = ""
    )

    /** A real quorum signing [eventId], in the manager's order. */
    private fun groupSignature(eventId: String): String {
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
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

    /** A template, signed by the group, the way a finished session leaves one. */
    private fun signed(template: EventTemplate<*>): Event {
        val unsigned = unsignedEventOf(template)

        return Event(
            id = unsigned.id,
            pubKey = unsigned.pubKey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = groupSignature(unsigned.id)
        )
    }

    /**
     * The claim, for one kind: sign it, file it as a row, rebuild it from the
     * row, and the group's signature still covers what comes out.
     */
    private fun assertRoundTrips(template: EventTemplate<*>, rebuild: (Event) -> Event?) {
        val signedEvent = signed(template)
        assertTrue(
            GroupKeyStateEvent.isSignedByRoom(signedEvent, chatRoomId),
            "the harness itself must produce a verifiable event"
        )

        val rebuilt = assertNotNull(
            rebuild(signedEvent),
            "kind ${template.kind} did not survive being turned into a row"
        )

        // Asserted before the signature so a failure says which half broke: an id
        // that moved means the tag order or a field changed in the rebuild, where
        // a signature that fails on a matching id would mean something stranger.
        assertEquals(
            signedEvent.id,
            rebuilt.id,
            "kind ${template.kind} came back with a different id"
        )
        assertEquals(
            signedEvent.sig,
            rebuilt.sig,
            "kind ${template.kind} lost the group's signature"
        )
        assertTrue(
            GroupKeyStateEvent.isSignedByRoom(rebuilt, chatRoomId),
            "kind ${template.kind} rebuilt into something the group's signature does not cover"
        )
    }

    @Test
    fun `a dialect survives the trip through a row`() {
        assertRoundTrips(
            DialectEvent.build(
                name = "isiZulu",
                country = "ZA",
                language = "zu",
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraDialect.fromDialectEvent(
                DialectEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig),
                chatRoomId
            )?.toDialectEvent()
        }
    }

    @Test
    fun `an artifact survives the trip through a row`() {
        // The one that did not, when this test was written. Two faults, and the
        // second is the interesting one: `toArtifactEvent` put the alt tag last
        // where `build` puts it first, and it left out the version metadata
        // entirely -- because that tag is not on the row at all. It is consumed
        // into the artifact's first MantraArtifactVersion and dropped. So the
        // label has to come back from there, which is why it is a parameter.
        assertRoundTrips(
            ArtifactEvent.build(
                name = "In Detention",
                url = "https://example.com/in-detention",
                visibility = "private",
                license = "cc",
                dialectId = dialectId,
                versionLabel = "1.0",
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraArtifact.fromArtifactEvent(
                ArtifactEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig),
                chatRoomId
            )?.toArtifactEvent(versionLabel = "1.0")
        }
    }

    @Test
    fun `an artifact rebuilt with the wrong version label is not the one that was signed`() {
        // What the parameter costs if a caller guesses. The label was part of
        // what the group put its signature to, so a rebuild carrying a different
        // one is a different event -- and it fails as a forgery rather than as a
        // mistake, which is why the assembler reads it off the initial version
        // rather than defaulting it.
        val signedEvent = signed(
            ArtifactEvent.build(
                name = "In Detention",
                url = "https://example.com/in-detention",
                visibility = "private",
                license = "cc",
                dialectId = dialectId,
                versionLabel = "1.0",
                createdAt = 1_700_000_000L
            )
        )

        val rebuilt = MantraArtifact.fromArtifactEvent(
            ArtifactEvent(
                signedEvent.id, signedEvent.pubKey, signedEvent.createdAt,
                signedEvent.tags, signedEvent.content, signedEvent.sig
            ),
            chatRoomId
        )?.toArtifactEvent(versionLabel = "2.0")

        assertNotNull(rebuilt)
        assertFalse(GroupKeyStateEvent.isSignedByRoom(rebuilt, chatRoomId))
    }

    @Test
    fun `an artifact version survives the trip through a row`() {
        // The second fault of the same shape, found the moment this kind became
        // chroniclable: `toArtifactVersionEvent` emitted [artifactId, alt] where
        // `build` emits [alt, artifactId]. It had been that way for as long as
        // nothing called it, which is what makes an unused rebuild dangerous
        // rather than merely dead -- the claim in its comment was never checked.
        //
        // Built through `initialVersionOf` rather than by hand, because that is
        // what the signing batch actually proposes.
        val artifact = signed(
            ArtifactEvent.build(
                name = "In Detention",
                url = "https://example.com/in-detention",
                visibility = "private",
                license = "cc",
                dialectId = dialectId,
                versionLabel = "1.0",
                createdAt = 1_700_000_000L
            )
        )

        assertRoundTrips(
            ArtifactVersionEvent.initialVersionOf(artifact).single()
        ) { event ->
            MantraArtifactVersion.fromArtifactVersionEvent(
                ArtifactVersionEvent(
                    event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
                ),
                chatRoomId
            )?.toArtifactVersionEvent()
        }
    }

    @Test
    fun `a translated chunk survives the trip through a row`() {
        // The translated text. Chroniclable only because the app stopped saving it
        // as its author's rumor and started asking the group to sign it -- until
        // then a new member got the whole structure and none of the prose.
        assertRoundTrips(
            TranslationChunkEvent.build(
                translationChapterId = translationChapterId,
                chunkId = chunkId,
                index = 0,
                text = "Wayeyindoda enezingxenye.",
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraTranslationChunk.fromTranslationChunkEvent(
                TranslationChunkEvent(
                    event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
                ),
                chatRoomId
            )?.toTranslationChunkEvent()
        }
    }

    @Test
    fun `a chapter survives the trip through a row`() {
        assertRoundTrips(
            ChapterEvent.build(
                artifactVersionId = artifactVersionId,
                name = "Chapter One",
                originalText = "He was a man of parts.",
                index = 0,
                wordCount = 6,
                characterCount = 22,
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraChapter.fromChapterEvent(
                ChapterEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig),
                chatRoomId
            )?.toChapterEvent()
        }
    }

    @Test
    fun `a chunk survives the trip through a row`() {
        assertRoundTrips(
            ChunkEvent.build(
                chapterId = chapterId,
                text = "He was a man of parts.",
                index = 0,
                wordCount = 6,
                characterCount = 22,
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraChunk.fromChunkEvent(
                ChunkEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig),
                chatRoomId
            )?.toChunkEvent()
        }
    }

    @Test
    fun `a translation artifact version survives the trip through a row`() {
        assertRoundTrips(
            TranslationArtifactVersionEvent.build(
                artifactVersionId = artifactVersionId,
                dialectId = dialectId,
                name = "isiZulu",
                visibility = "private",
                license = "cc",
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraTranslationArtifactVersion.fromTranslationArtifactVersionEvent(
                TranslationArtifactVersionEvent(
                    event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
                ),
                chatRoomId
            )?.toTranslationArtifactVersionEvent()
        }
    }

    @Test
    fun `a translation chapter survives the trip through a row`() {
        assertRoundTrips(
            TranslationChapterEvent.build(
                translationArtifactVersionId = translationArtifactVersionId,
                chapterId = chapterId,
                index = 0,
                createdAt = 1_700_000_000L
            )
        ) { event ->
            MantraTranslationChapter.fromTranslationChapterEvent(
                TranslationChapterEvent(
                    event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
                ),
                chatRoomId
            )?.toTranslationChapterEvent()
        }
    }

    @Test
    fun `every chroniclable kind has a round-trip test above`() {
        // The list and this file have to move together. A kind added to the
        // allowlist without a case here is one nobody has checked can be rebuilt,
        // and the failure is silent: the receiver drops it as a forgery.
        assertEquals(
            8,
            ChronicleEvent.CHRONICLABLE_KINDS.size,
            "a chroniclable kind was added or removed; add or remove its round-trip case"
        )
    }
}
