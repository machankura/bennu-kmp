package com.machankura.bennu.compose.nostr

import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.managers.SharedKeyDerivation
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaProblem
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a pasted event may become, and where it goes once the group has signed it.
 *
 * Two halves. The first is the reading: which pastes are refused and why, and
 * that what is refused is exactly what a reader would refuse -- the point of
 * checking before proposing is that a quorum is never asked to sign something
 * that will be filed and shown nowhere.
 *
 * The second is the promise the feature makes: a pasted kind 0 becomes the
 * profile, a kind 1 a post, a kind 31889 a schema, a relay list a relay list. That
 * is not this module's doing -- `FrostSigningManager.complete` files every signed
 * event and the group's screen reads each section off those by kind -- so the
 * test is the whole chain: the paste read here, signed by a real quorum through
 * the same shape `FrostSigningManager.advance` runs, and read back by the reader
 * that section uses.
 */
class GroupEventProposalTest {
    private val participants = 3
    private val threshold = 2

    private val groupMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    private val chatRoomId =
        SharedKeyDerivation.marmotGroupId(groupMaterial.thresholdPublicKey.value.toHex(), path)

    /**
     * The bitcoin.mov schema as `npm run schema:dry` would print it: a whole
     * event, id, author, signature and time included, abbreviated to the tags
     * a reading depends on.
     */
    private val pastedSchema = """
        {
          "id": "b2f8c7a7b4d5e6f70123456789abcdef0123456789abcdef0123456789abcdef",
          "pubkey": "7c965d8c2acdfd635562da3bcb82596b595be28b008d8b54ec702ed4c67d9d25",
          "created_at": 1735689600,
          "kind": 31889,
          "tags": [
            ["d", "bitcoin.mov"],
            ["title", "bitcoin.mov suggestion"],
            ["name", "bitcoin.mov"],
            ["description", "Fields for a bitcoin.mov entry."],
            ["k", "31888"],
            ["visibility", "public"],
            ["domain", "bitcoin.mov"],
            ["relay", "wss://ephemeral.mantra.press"],
            ["field", "identifier", "token", "required", "", "Identifier", "{\"tag\":\"d\",\"max\":80,\"derived\":true}"],
            ["field", "title", "text", "required", "The Rise and Rise of Bitcoin", "Title", "{\"max\":200}"],
            ["field", "watchUrl", "url", "optional", "", "Watch / reference URL", "{\"tag\":\"r\",\"marker\":\"watch\",\"repeat\":true}"]
          ],
          "content": "Fields for a bitcoin.mov entry.",
          "sig": "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00"
        }
    """.trimIndent()

    @Test
    fun `a pasted event is read as its kind tags and content and nothing else`() {
        val ready = assertIs<GroupEventProposal.Ready>(GroupEventProposal.read(pastedSchema))

        assertEquals(31889, ready.kind)
        assertEquals("Fields for a bitcoin.mov entry.", ready.content)
        assertEquals(11, ready.tags.size)
        assertEquals(listOf("d", "bitcoin.mov"), ready.tags.first().toList())
        // The config blob comes through verbatim, escapes and all.
        assertEquals("{\"tag\":\"d\",\"max\":80,\"derived\":true}", ready.tags[8][6])

        // The id, author, signature and time are not the paste's to decide, and
        // there is nowhere on a reading for them to go: the session hashes the id
        // over the room's key and the time it opens at.
        val preview = ready.preview()
        assertEquals("", preview.pubKey)
        assertEquals(0L, preview.createdAt)
    }

    @Test
    fun `a bare template with no tags or content still reads`() {
        val ready = assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read("""{"kind": 0, "content": "{}"}""")
        )
        assertEquals(0, ready.kind)
        assertEquals(0, ready.tags.size)

        // A numeric string for the kind is what a hand types; a relay writes a number.
        assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read("""{"kind": "1", "content": "Hello"}""")
        )
    }

    @Test
    fun `what is not an event is refused and named`() {
        assertEquals(GroupEventProposal.Refused.NotJson, GroupEventProposal.read("not json at all"))
        assertEquals(GroupEventProposal.Refused.NotJson, GroupEventProposal.read("{\"kind\": 1,"))
        assertEquals(GroupEventProposal.Refused.NotAnObject, GroupEventProposal.read("[1, 2, 3]"))
        assertEquals(GroupEventProposal.Refused.NotAnObject, GroupEventProposal.read("\"a string\""))
        assertEquals(GroupEventProposal.Refused.NoKind, GroupEventProposal.read("""{"content": "Hello"}"""))
        assertEquals(GroupEventProposal.Refused.NoKind, GroupEventProposal.read("""{"kind": "one"}"""))
        assertEquals(
            GroupEventProposal.Refused.TagsMalformed,
            GroupEventProposal.read("""{"kind": 1, "tags": "e", "content": "Hello"}""")
        )
        assertEquals(
            GroupEventProposal.Refused.TagsMalformed,
            GroupEventProposal.read("""{"kind": 1, "tags": [["e", 1]], "content": "Hello"}""")
        )
        assertEquals(
            GroupEventProposal.Refused.ContentNotText,
            GroupEventProposal.read("""{"kind": 1, "content": {"note": "Hello"}}""")
        )
    }

    @Test
    fun `a kind the group's screen has no place for is refused whatever else it says`() {
        // A long-form article is a perfectly good nostr event and the group could
        // sign it. It would then be filed and shown nowhere, with nothing to tell
        // the member their quorum was spent on it.
        assertEquals(
            GroupEventProposal.Refused.KindNotAccepted(30023),
            GroupEventProposal.read("""{"kind": 30023, "tags": [["d", "x"]], "content": "An article"}""")
        )

        // The kinds with a section: the profile, the posts, the four relay lists
        // and the schemas. A subgroup certificate is deliberately not among them.
        assertEquals(
            setOf(0, 1, 10002, 10050, 10007, 10006, 31889),
            GroupEventProposal.ACCEPTED_KINDS
        )
    }

    @Test
    fun `a kind zero whose content is not a profile is refused and an empty one is not`() {
        assertEquals(
            GroupEventProposal.Refused.ProfileUnreadable,
            GroupEventProposal.read("""{"kind": 0, "content": "not a profile"}""")
        )

        // Wiping a profile is a thing groups are entitled to do -- the same line
        // `GroupNostrProfile.of` draws.
        assertIs<GroupEventProposal.Ready>(GroupEventProposal.read("""{"kind": 0, "content": ""}"""))
        assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read("""{"kind": 0, "content": "{\"name\":\"Translation collective\"}"}""")
        )
    }

    @Test
    fun `a blank post is refused the way the composer refuses one`() {
        listOf("", "   ", "\\n\\n").forEach { blank ->
            assertEquals(
                GroupEventProposal.Refused.PostBlank,
                GroupEventProposal.read("""{"kind": 1, "content": "$blank"}"""),
                "a post saying \"$blank\" was accepted"
            )
        }
    }

    @Test
    fun `a schema a reader would refuse is refused here with the reader's reasons`() {
        val withoutVisibility = pastedSchema.replace("""["visibility", "public"],""", "")

        assertEquals(
            GroupEventProposal.Refused.SchemaUnusable(listOf(CuratedSchemaProblem.VisibilityMissing)),
            GroupEventProposal.read(withoutVisibility)
        )
    }

    @Test
    fun `a relay list reads whether or not it names a relay`() {
        // An empty one is a list the group is withdrawing, which the relays card
        // shows as "no relays" rather than as nothing.
        assertIs<GroupEventProposal.Ready>(GroupEventProposal.read("""{"kind": 10002}"""))
        assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read("""{"kind": 10002, "tags": [["r", "wss://relay.example.com", "write"]]}""")
        )
    }

    @Test
    fun `a pasted kind zero once signed is the group's profile`() {
        val ready = assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read(
                """{"kind": 0, "pubkey": "not the group", "content": "{\"name\":\"Translation collective\",\"about\":\"We translate hard books.\"}"}"""
            )
        )

        val profile = assertNotNull(GroupNostrProfile.of(signed(ready), chatRoomId))

        assertEquals("Translation collective", profile.name())
        assertEquals("We translate hard books.", profile.about())
        assertEquals(chatRoomId, profile.publicKey)
    }

    @Test
    fun `a pasted kind one once signed is one of the group's posts`() {
        val ready = assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read("""{"kind": 1, "tags": [], "content": "We have finished the first chapter."}""")
        )

        val posts = GroupPost.newestFirstAmong(listOf(signed(ready)), chatRoomId)

        assertEquals(listOf("We have finished the first chapter."), posts.map { it.content })
        assertTrue(posts.single().isNewThread())
    }

    @Test
    fun `a pasted schema once signed is a list the group curates`() {
        val ready = assertIs<GroupEventProposal.Ready>(GroupEventProposal.read(pastedSchema))

        val curated = assertNotNull(GroupCuratedSchema.of(signed(ready), chatRoomId))

        assertEquals("bitcoin.mov", curated.identifier)
        assertEquals("bitcoin.mov", curated.schema.displayName())
        assertEquals(CuratedVisibility.Public, curated.schema.visibility)
        assertEquals(listOf("identifier", "title", "watchUrl"), curated.schema.fields.map { it.name })
        // The coordinate is the group's, not the pasted pubkey's: the group is
        // the curator of what it signed.
        assertEquals("${CuratedSchemaEvent.KIND}:$chatRoomId:bitcoin.mov", curated.coordinate())
    }

    @Test
    fun `a pasted relay list once signed is one of the group's relay lists`() {
        val ready = assertIs<GroupEventProposal.Ready>(
            GroupEventProposal.read("""{"kind": 10002, "tags": [["r", "wss://relay.example.com", "write"]]}""")
        )

        val list = GroupRelayList.newestAmong(listOf(signed(ready)), chatRoomId, GroupRelaySet.General)

        assertTrue(list.isAgreed)
        assertEquals(listOf("relay.example.com"), list.relays.map { it.displayUrl() })
        assertEquals(listOf(false), list.relays.map { it.read })
        assertEquals(listOf(true), list.relays.map { it.write })
    }

    /**
     * A reading as `FrostSigningManager.complete` would file it: authored by the
     * room's key at its path, the id hashed over that, a real quorum's signature
     * on it, and the time the session opened at rather than anything pasted.
     */
    private fun signed(ready: GroupEventProposal.Ready, createdAt: Long = 1_700_000_000): GroupSignedEvent {
        val groupPubKey = SharedKeyDerivation
            .derive(groupMaterial.thresholdPublicKey.value.toHex(), path)
            .hex

        val id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = ready.kind,
            tags = ready.tags,
            content = ready.content
        )

        return GroupSignedEvent.fromEvent(
            event = Event(
                id = id,
                pubKey = groupPubKey,
                createdAt = createdAt,
                kind = ready.kind,
                tags = ready.tags,
                content = ready.content,
                sig = groupSignature(groupMaterial, id)
            ),
            chatRoomId = chatRoomId,
            derivationPath = SharedKeyDerivation.formatPath(path)
        )
    }

    /**
     * A real FROST signature by [material]'s quorum over [eventId], through the
     * same shape `FrostSigningManager.advance` runs.
     */
    private fun groupSignature(material: KeyMaterial, eventId: String): String {
        val cache = SharedKeyDerivation
            .derive(material.thresholdPublicKey.value.toHex(), path)
            .cache
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = material.secretShares[signerId],
                publicShare = material.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val signingSession = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { material.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            signingSession.sign(
                nonces[position].first,
                material.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }

        return signingSession.aggregateSigs(partials).right!!.toByteArray().toHex()
    }
}
