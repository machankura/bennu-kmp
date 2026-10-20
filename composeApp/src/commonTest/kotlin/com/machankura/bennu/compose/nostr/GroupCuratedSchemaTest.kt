package com.machankura.bennu.compose.nostr

import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.managers.SharedKeyDerivation
import com.machankura.bennu.compose.nostr.curated.CuratedField
import com.machankura.bennu.compose.nostr.curated.CuratedFieldConfig
import com.machankura.bennu.compose.nostr.curated.CuratedFieldType
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What may be shown as a list the group curates.
 *
 * The reading gate is `GroupPostTest`'s -- the room has to be the author and the
 * signature has to be the room's -- and it carries a weight of its own here: a
 * schema read wrong would have strangers filling in a form under a group's name
 * that the group never agreed to, and the group's key accepting entries against
 * it. The third refusal is this reader's alone: a schema the room really signed
 * but that no client could act on is not shown as one it curates.
 *
 * The ordering half is between the profile's and the posts': one per identifier,
 * newest winning within it, because the kind is addressable and a group may
 * curate several lists.
 */
class GroupCuratedSchemaTest {
    private val participants = 3
    private val threshold = 2

    private val groupMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** Another group entirely, for the schemas signed by the wrong room. */
    private val strangerMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("3decade0000000000000000000000000000000000000000000000000000000c3")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    private val chatRoomId =
        SharedKeyDerivation.marmotGroupId(groupMaterial.thresholdPublicKey.value.toHex(), path)

    private val films = CuratedSchema(
        identifier = "films",
        title = "Film suggestion",
        name = "Films worth translating",
        description = "Films the group would subtitle, with a link to watch each one.",
        visibility = CuratedVisibility.Public,
        fields = listOf(
            CuratedField.IDENTIFIER,
            CuratedField.TITLE,
            CuratedField(
                name = "watchUrl",
                type = CuratedFieldType.Url,
                isRequired = true,
                placeholder = "https://…",
                label = "Where to watch",
                config = CuratedFieldConfig(tag = "r", marker = "watch", max = 500, https = true),
            ),
        ),
        relays = listOf("wss://relay.example.com"),
    )

    @Test
    fun `a schema the room signed is a list the group curates`() {
        val curated = assertNotNull(
            GroupCuratedSchema.of(signedEvent = signed(films), chatRoomId = chatRoomId)
        )

        assertEquals("films", curated.identifier)
        assertEquals(chatRoomId, curated.publicKey)
        assertEquals("31889:$chatRoomId:films", curated.coordinate())
        assertEquals(films, curated.schema)
        assertEquals("Films worth translating", curated.schema.displayName())
    }

    @Test
    fun `a schema another group signed is not this group's`() {
        // A real quorum and a real signature by a group with no standing to
        // curate for this one. The row names this room because that is where it
        // was filed; the author is what decides whose list it is.
        val stranger = GroupSignedEvent.fromEvent(
            event = schemaEvent(films, material = strangerMaterial),
            chatRoomId = chatRoomId
        )

        assertNull(GroupCuratedSchema.of(signedEvent = stranger, chatRoomId = chatRoomId))
    }

    @Test
    fun `a schema the room did not sign is not one`() {
        // Authored by the room and signed by somebody else: the shape a member
        // declaring a list in the group's name would produce, and the reason the
        // reading verifies rather than trusting the author field.
        assertNull(
            GroupCuratedSchema.of(
                signedEvent = signed(films, signer = strangerMaterial),
                chatRoomId = chatRoomId
            )
        )

        listOf("f".repeat(128), "not a signature", "").forEach { rubbish ->
            assertNull(
                GroupCuratedSchema.of(
                    signedEvent = signed(films, signature = rubbish),
                    chatRoomId = chatRoomId
                ),
                "a schema signed with \"$rubbish\" was accepted"
            )
        }
    }

    @Test
    fun `an event of another kind is not a schema`() {
        assertNull(
            GroupCuratedSchema.of(
                signedEvent = signed(films).copy(kind = CuratedSchemaEvent.SUGGESTION_KIND),
                chatRoomId = chatRoomId
            )
        )
    }

    @Test
    fun `a schema the room signed but no client could act on is refused`() {
        // The room's real signature over a kind 31889 with no visibility. It is
        // the group's statement and it is still not a list: a reader that showed
        // it would be showing a form nobody may be allowed to fill in.
        val unusable = signed(
            films,
            tags = CuratedSchemaEvent.template(films, createdAt = 1_700_000_000).tags
                .filterNot { it[0] == "visibility" }
                .toTypedArray()
        )

        assertNull(GroupCuratedSchema.of(signedEvent = unusable, chatRoomId = chatRoomId))
        assertEquals(emptyList(), GroupCuratedSchema.newestPerListAmong(listOf(unusable), chatRoomId))
    }

    @Test
    fun `the newest per identifier wins and two identifiers are two lists`() {
        val firstFilms = signed(films, createdAt = 1_700_000_000)
        val editedFilms = signed(films.copy(description = "Films the group would subtitle."), createdAt = 1_700_000_900)
        val books = signed(films.copy(identifier = "books", name = "Books worth translating"), createdAt = 1_700_000_300)

        listOf(
            listOf(firstFilms, editedFilms, books),
            listOf(books, editedFilms, firstFilms),
            listOf(editedFilms, books, firstFilms),
        ).forEach { events ->
            val lists = GroupCuratedSchema.newestPerListAmong(events, chatRoomId)

            assertEquals(
                listOf("films", "books"),
                lists.map { it.identifier },
                "the lists came back in the wrong order, or one of them went missing"
            )
            assertEquals(
                "Films the group would subtitle.",
                lists.first().schema.description,
                "an edit did not replace the schema it revised"
            )
        }
    }

    @Test
    fun `two versions signed in the same second resolve the same way on every device`() {
        val one = signed(films.copy(description = "One."), createdAt = 1_700_000_000)
        val other = signed(films.copy(description = "Other."), createdAt = 1_700_000_000)

        val expected = GroupCuratedSchema.newestPerListAmong(listOf(one, other), chatRoomId).single()

        assertEquals(
            expected,
            GroupCuratedSchema.newestPerListAmong(listOf(other, one), chatRoomId).single(),
            "a tie on the timestamp was broken differently by the two orderings"
        )
        assertEquals(
            if (one.id > other.id) "One." else "Other.",
            expected.schema.description,
            "the tie should break on the id, which is the only thing both devices share"
        )
    }

    @Test
    fun `a schema the editor built reads back as the group defined it`() {
        val template = GroupCuratedSchema.template(films)

        assertEquals(CuratedSchemaEvent.KIND, template.kind)
        assertEquals(films.description, template.content)

        val curated = assertNotNull(
            GroupCuratedSchema.of(
                signedEvent = signed(films, tags = template.tags, content = template.content),
                chatRoomId = chatRoomId
            )
        )

        assertEquals(films, curated.schema)
        assertTrue(curated.schema.fields.first { it.name == "watchUrl" }.isRequired)
    }

    /** A schema as `FrostSigningManager.complete` files one. */
    private fun signed(
        schema: CuratedSchema,
        tags: Array<Array<String>> = CuratedSchemaEvent.template(schema, createdAt = 1_700_000_000).tags,
        content: String = schema.description,
        createdAt: Long = 1_700_000_000,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): GroupSignedEvent = GroupSignedEvent.fromEvent(
        event = schemaEvent(schema, tags, content, createdAt, material, signer, signature),
        chatRoomId = chatRoomId,
        derivationPath = SharedKeyDerivation.formatPath(path)
    )

    private fun schemaEvent(
        schema: CuratedSchema,
        tags: Array<Array<String>> = CuratedSchemaEvent.template(schema, createdAt = 1_700_000_000).tags,
        content: String = schema.description,
        createdAt: Long = 1_700_000_000,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): Event {
        val groupPubKey = SharedKeyDerivation
            .derive(material.thresholdPublicKey.value.toHex(), path)
            .hex

        val id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = CuratedSchemaEvent.KIND,
            tags = tags,
            content = content
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = CuratedSchemaEvent.KIND,
            tags = tags,
            content = content,
            sig = signature ?: groupSignature(signer, id)
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
