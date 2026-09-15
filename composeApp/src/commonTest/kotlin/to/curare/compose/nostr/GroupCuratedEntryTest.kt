package to.curare.compose.nostr

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
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.extensions.toHex
import to.curare.compose.managers.SharedKeyDerivation
import to.curare.compose.nostr.curated.CuratedEntryEvent
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldConfig
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedSuggestionRef
import to.curare.compose.nostr.curated.CuratedVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What may be shown as an entry the group accepted into a list it curates.
 *
 * The reading gate is `GroupCuratedSchemaTest`'s -- the room has to be the
 * author and the signature has to be the room's -- plus the two things this
 * reader alone decides. An entry is read against the list it replies to, so
 * one replying to a list this device holds no schema for is not shown, and one
 * the list's own fields refuse is not shown either: it is the check the queue
 * makes on a relay's copy, made here on the group's own, so the two screens
 * cannot disagree about what the group accepted.
 *
 * The ordering half is the schema's: one per coordinate, newest winning, since
 * re-curating is a newer event under the same `d`.
 */
class GroupCuratedEntryTest {
    private val participants = 3
    private val threshold = 2

    private val groupMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** Another group entirely, for the entries signed by the wrong room. */
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

    private val suggester = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"

    private val films = CuratedSchema(
        identifier = "films",
        title = "Film suggestion",
        name = "Films worth translating",
        description = "Films the group would subtitle, with a link to watch each one.",
        visibility = CuratedVisibility.Public,
        fields = listOf(
            CuratedField.IDENTIFIER,
            CuratedField.TITLE,
            CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year", config = CuratedFieldConfig(min = 1900, max = 2100)),
            CuratedField(
                name = "watchUrl",
                type = CuratedFieldType.Url,
                isRequired = true,
                label = "Where to watch",
                config = CuratedFieldConfig(tag = "r", marker = "watch", max = 500, https = true),
            ),
        ),
        relays = listOf("wss://relay.example.com"),
    )

    private val books = films.copy(identifier = "books", name = "Books worth translating")

    /** The lists as the group's screen holds them: read off the group's real signatures. */
    private val schemas: List<GroupCuratedSchema> = listOf(films, books).map { schema ->
        assertNotNull(GroupCuratedSchema.of(signed(CuratedSchemaEvent.template(schema, createdAt = 1_700_000_000)), chatRoomId))
    }

    private val filmsList = schemas.first { it.identifier == "films" }
    private val booksList = schemas.first { it.identifier == "books" }

    private fun riseAndRise(year: String = "2014") = mapOf(
        "identifier" to listOf("imdb:tt2821314"),
        "title" to listOf("The Rise and Rise of Bitcoin"),
        "year" to listOf(year),
        "watchUrl" to listOf("https://bitcoindoc.com/"),
    )

    private val source = CuratedSuggestionRef(
        id = "a".repeat(64),
        coordinate = "31888:$suggester:imdb:tt2821314",
        pubKey = suggester,
    )

    private fun entry(
        list: GroupCuratedSchema = filmsList,
        values: Map<String, List<String>> = riseAndRise(),
        createdAt: Long = 1_700_000_900,
    ): EventTemplate<*> = CuratedEntryEvent.canonicalTemplate(
        schema = list.schema,
        coordinate = list.coordinate(),
        curator = list.publicKey,
        values = values,
        source = source,
        createdAt = createdAt,
    )

    @Test
    fun `an entry the room signed into a list it curates is one it accepted`() {
        val accepted = assertNotNull(GroupCuratedEntry.of(signed(entry()), chatRoomId, schemas))

        assertEquals("The Rise and Rise of Bitcoin", accepted.title)
        assertEquals("imdb:tt2821314", accepted.identifier)
        assertEquals("31890:$chatRoomId:imdb:tt2821314", accepted.coordinate())
        assertEquals(filmsList, accepted.list)
        assertEquals("2014", accepted.entry.value("year"))
        assertEquals(source, accepted.entry.source)
    }

    @Test
    fun `an entry another group signed is not this group's`() {
        val stranger = GroupSignedEvent.fromEvent(
            event = event(entry(), material = strangerMaterial),
            chatRoomId = chatRoomId
        )

        assertNull(GroupCuratedEntry.of(stranger, chatRoomId, schemas))
    }

    @Test
    fun `an entry the room did not sign is not one`() {
        // Authored by the room and signed by somebody else: a member declaring
        // the group accepted something, which the reading verifies rather than
        // trusting the author field.
        assertNull(GroupCuratedEntry.of(signed(entry(), signer = strangerMaterial), chatRoomId, schemas))

        listOf("f".repeat(128), "not a signature", "").forEach { rubbish ->
            assertNull(
                GroupCuratedEntry.of(signed(entry(), signature = rubbish), chatRoomId, schemas),
                "an entry signed with \"$rubbish\" was accepted"
            )
        }
    }

    @Test
    fun `an event of another kind is not an entry`() {
        assertNull(
            GroupCuratedEntry.of(signed(entry()).copy(kind = CuratedEntryEvent.SUGGESTION_KIND), chatRoomId, schemas)
        )
    }

    @Test
    fun `an entry into a list this device holds no schema for is not shown`() {
        // Really signed, and replying to a real list -- just not one among the
        // schemas the reader was given. There are no fields to read it by.
        assertNull(GroupCuratedEntry.of(signed(entry(list = booksList)), chatRoomId, schemas = listOf(filmsList)))
    }

    @Test
    fun `an entry the list's own fields refuse is not shown`() {
        // The room's real signature over a year outside the schema's range: the
        // group's statement, and still not an entry the list's readers would
        // count. The queue would drop the relay's copy for the same reason.
        val outOfRange = signed(entry(values = riseAndRise(year = "1850")))

        assertNull(GroupCuratedEntry.of(outOfRange, chatRoomId, schemas))
        assertEquals(emptyList(), GroupCuratedEntry.newestPerEntryAmong(listOf(outOfRange), chatRoomId, schemas))
    }

    @Test
    fun `the newest per coordinate wins and two identifiers are two entries`() {
        val first = signed(entry(createdAt = 1_700_000_900))
        val revised = signed(entry(values = riseAndRise(year = "2015"), createdAt = 1_700_001_800))
        val another = signed(
            entry(
                values = mapOf(
                    "identifier" to listOf("magic-money"),
                    "title" to listOf("Magic Money"),
                    "watchUrl" to listOf("https://example.com/watch"),
                ),
                createdAt = 1_700_001_200,
            )
        )

        listOf(
            listOf(first, revised, another),
            listOf(another, revised, first),
            listOf(revised, another, first),
        ).forEach { events ->
            val accepted = GroupCuratedEntry.newestPerEntryAmong(events, chatRoomId, schemas)

            assertEquals(
                listOf("imdb:tt2821314", "magic-money"),
                accepted.map { it.identifier },
                "the entries came back in the wrong order, or one of them went missing"
            )
            assertEquals("2015", accepted.first().entry.value("year"), "a re-curation did not replace the entry it revised")
        }
    }

    @Test
    fun `the same identifier in two lists is one coordinate and the newer decides`() {
        val inFilms = signed(entry(list = filmsList, createdAt = 1_700_000_900))
        val inBooks = signed(entry(list = booksList, createdAt = 1_700_001_800))

        val accepted = GroupCuratedEntry.newestPerEntryAmong(listOf(inFilms, inBooks), chatRoomId, schemas)

        assertEquals(1, accepted.size, "one coordinate was shown as two entries")
        assertEquals(booksList, accepted.single().list)
    }

    /** An entry as `FrostSigningManager.complete` files one. */
    private fun signed(
        template: EventTemplate<*>,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): GroupSignedEvent = GroupSignedEvent.fromEvent(
        event = event(template, material, signer, signature),
        chatRoomId = chatRoomId,
        derivationPath = SharedKeyDerivation.formatPath(path)
    )

    private fun event(
        template: EventTemplate<*>,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): Event {
        val groupPubKey = SharedKeyDerivation
            .derive(material.thresholdPublicKey.value.toHex(), path)
            .hex

        val id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
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
