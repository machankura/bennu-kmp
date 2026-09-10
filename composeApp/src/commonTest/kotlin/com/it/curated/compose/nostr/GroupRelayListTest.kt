package com.it.curated.compose.nostr

import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.extensions.toHex
import com.it.curated.compose.managers.SharedKeyDerivation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a group's relay lists are allowed to be read out of, and what an edit
 * produces.
 *
 * The reading half is `GroupNostrProfileTest`'s again -- the room has to be the
 * author, the signature has to be the room's -- and it is repeated here because
 * these are four different kinds through two different tag vocabularies, and a
 * mistake in any one of them would be invisible in the other three.
 *
 * The writing half is the one that could not be settled by reading the code.
 * NIP-65's markers are absent for both, `read` for read-only and `write` for
 * write-only, so the tag for the ordinary case is the one with the least in it --
 * exactly the shape a round trip is most likely to lose. And "neither" is a state
 * the format cannot express at all, which is why the editor refuses it and why
 * building one drops it rather than writing a tag that means the opposite.
 */
class GroupRelayListTest {
    private val participants = 3
    private val threshold = 2

    private val groupMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** Another group entirely, for the lists signed by the wrong room. */
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

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")

    @Test
    fun `every set round trips through the tags it is written in`() {
        // Four kinds and two tag vocabularies: NIP-65's "r" for General and the
        // "relay" tag for the other three, through two RelayTag classes that are
        // different classes in different packages. A set wired to the wrong one
        // writes tags nothing reads back.
        GroupRelaySet.entries.forEach { set ->
            val relays = listOf(GroupRelay(one), GroupRelay(two))
            val list = GroupRelayList.of(
                signedEvent = signed(set.template(relays)),
                chatRoomId = chatRoomId,
                set = set
            )

            assertEquals(
                listOf(one, two),
                list?.relays?.map { it.url },
                "${set.name} did not round trip through its own tags"
            )
        }
    }

    @Test
    fun `a relay list only reads back as the set it was written as`() {
        // The kinds are the only thing separating a general list from a blocked
        // one, and reading a blocked list as a general one would have the group
        // publishing to the relays it refuses to talk to.
        val blocked = signed(GroupRelaySet.Blocked.template(listOf(GroupRelay(one))))

        assertNull(GroupRelayList.of(blocked, chatRoomId, GroupRelaySet.General))
        assertNull(GroupRelayList.of(blocked, chatRoomId, GroupRelaySet.Search))
        assertEquals(
            listOf(one),
            GroupRelayList.of(blocked, chatRoomId, GroupRelaySet.Blocked)?.relays?.map { it.url }
        )
    }

    @Test
    fun `read and write markers survive being written and read`() {
        // The ordinary case is the dangerous one: NIP-65 writes both by *omitting*
        // the marker, so a round trip that lost the third element entirely would
        // still look correct for a both-ways relay and would silently turn a
        // read-only relay into a write one.
        val relays = listOf(
            GroupRelay(one, read = true, write = true),
            GroupRelay(two, read = true, write = false),
            GroupRelay(RelayUrlNormalizer.normalize("wss://relay.three.example"),
                read = false, write = true),
        )

        val readBack = GroupRelayList.of(
            signedEvent = signed(GroupRelaySet.General.template(relays)),
            chatRoomId = chatRoomId,
            set = GroupRelaySet.General
        )

        assertEquals(relays, readBack?.relays)
    }

    @Test
    fun `a relay that is neither read nor write is not written at all`() {
        // NIP-65 has no marker for it. The editor will not produce the state, and
        // this is the second line of that defence: writing a bare `r` tag would
        // advertise it as both, which is the opposite of what was asked.
        val list = GroupRelayList.of(
            signedEvent = signed(
                GroupRelaySet.General.template(
                    listOf(
                        GroupRelay(one, read = false, write = false),
                        GroupRelay(two),
                    )
                )
            ),
            chatRoomId = chatRoomId,
            set = GroupRelaySet.General
        )

        assertEquals(listOf(two), list?.relays?.map { it.url })
    }

    @Test
    fun `a relay list another group signed is not this group's`() {
        val stranger = GroupSignedEvent.fromEvent(
            event = relayEvent(
                GroupRelaySet.General.template(listOf(GroupRelay(one))),
                material = strangerMaterial
            ),
            chatRoomId = chatRoomId
        )

        assertNull(GroupRelayList.of(stranger, chatRoomId, GroupRelaySet.General))
    }

    @Test
    fun `a relay list the room did not sign is not a relay list`() {
        // Authored by the room and signed by somebody else -- the shape a member
        // redirecting a group's traffic would produce.
        assertNull(
            GroupRelayList.of(
                signedEvent = signed(
                    GroupRelaySet.General.template(listOf(GroupRelay(one))),
                    signer = strangerMaterial
                ),
                chatRoomId = chatRoomId,
                set = GroupRelaySet.General
            )
        )

        listOf("f".repeat(128), "not a signature", "").forEach { rubbish ->
            assertNull(
                GroupRelayList.of(
                    signedEvent = signed(
                        GroupRelaySet.General.template(listOf(GroupRelay(one))),
                        signature = rubbish
                    ),
                    chatRoomId = chatRoomId,
                    set = GroupRelaySet.General
                ),
                "a list signed with \"$rubbish\" was accepted"
            )
        }
    }

    @Test
    fun `a set the group never agreed reads back unsigned and empty`() {
        // Not null. The screen has a row per set whatever the group has said, and
        // `isAgreed` is what separates "never said" from "said nothing".
        val list = GroupRelayList.newestAmong(emptyList(), chatRoomId, GroupRelaySet.Search)

        assertFalse(list.isAgreed)
        assertEquals(emptyList(), list.relays)
        assertEquals(GroupRelaySet.Search, list.set)
        assertNull(list.signedAt)
    }

    @Test
    fun `an agreed empty list is not the same as one never agreed`() {
        // A group withdrawing its relay list signs an empty one, and that decision
        // has to survive being read back -- otherwise a deliberate withdrawal looks
        // exactly like never having got round to it.
        val list = GroupRelayList.of(
            signedEvent = signed(GroupRelaySet.Messages.template(emptyList())),
            chatRoomId = chatRoomId,
            set = GroupRelaySet.Messages
        )

        assertTrue(list?.isAgreed == true)
        assertEquals(emptyList(), list?.relays)
    }

    @Test
    fun `the newest list the group signed is the one that counts`() {
        val older = signed(
            GroupRelaySet.General.template(listOf(GroupRelay(one))),
            createdAt = 1_700_000_000
        )
        val newer = signed(
            GroupRelaySet.General.template(listOf(GroupRelay(two))),
            createdAt = 1_700_000_900
        )

        listOf(listOf(older, newer), listOf(newer, older)).forEach { events ->
            assertEquals(
                listOf(two),
                GroupRelayList.newestAmong(events, chatRoomId, GroupRelaySet.General)
                    .relays.map { it.url },
                "the newest relay list lost to query order"
            )
        }
    }

    @Test
    fun `allAmong sorts every set into one entry each`() {
        val events = listOf(
            signed(GroupRelaySet.General.template(listOf(GroupRelay(one)))),
            signed(GroupRelaySet.Blocked.template(listOf(GroupRelay(two)))),
        )

        val all = GroupRelayList.allAmong(events, chatRoomId)

        assertEquals(GroupRelaySet.entries.toList(), all.map { it.set })
        assertEquals(listOf(one), all.first { it.set == GroupRelaySet.General }.relays.map { it.url })
        assertEquals(listOf(two), all.first { it.set == GroupRelaySet.Blocked }.relays.map { it.url })
        assertFalse(all.first { it.set == GroupRelaySet.Search }.isAgreed)
        assertFalse(all.first { it.set == GroupRelaySet.Messages }.isAgreed)
    }

    @Test
    fun `every set but blocked starts at the app's own relay`() {
        // What a group setting up for the first time is shown. Seeding with nothing
        // would make its first proposal an empty list, which says less than never
        // having said anything.
        GroupRelaySet.entries.filterNot { it == GroupRelaySet.Blocked }.forEach { set ->
            assertEquals(
                listOf(Relays.ephemeral),
                set.defaults().map { it.url },
                "${set.name} did not default to the app's relay"
            )
        }

        // And blocked starts empty: a default there is a refusal to talk to
        // somebody that nobody in the group chose.
        assertEquals(emptyList(), GroupRelaySet.Blocked.defaults())
    }

    @Test
    fun `only a wss relay somewhere other than this device is accepted`() {
        assertEquals(one, GroupRelaySet.relayUrlOrNull("wss://relay.one.example"))
        assertEquals(one, GroupRelaySet.relayUrlOrNull("  wss://relay.one.example  "))

        // A group's list is read by strangers over the open network: ws:// asks
        // them to fetch it in the clear, and loopback names a relay only one device
        // can reach.
        listOf(
            "ws://relay.one.example",
            "http://relay.one.example",
            "relay.one.example",
            "wss://localhost",
            "wss://127.0.0.1",
            "",
        ).forEach {
            assertNull(GroupRelaySet.relayUrlOrNull(it), "\"$it\" was accepted as a relay")
        }
    }

    @Test
    fun `a duplicated relay is read once`() {
        // Two tags for one relay is one relay said twice, and a list that shows it
        // twice reads as two to a human -- the same reasoning a birth certificate's
        // admin roster is deduplicated on.
        val duplicated = Event(
            id = "",
            pubKey = chatRoomId,
            createdAt = 1_700_000_000,
            kind = AdvertisedRelayListEvent.KIND,
            tags = arrayOf(arrayOf("r", one.url), arrayOf("r", one.url, "read")),
            content = "",
            sig = ""
        )

        assertEquals(listOf(one), GroupRelaySet.General.parse(duplicated.tags).map { it.url })
    }

    /** A relay list as `FrostSigningManager.complete` files one. */
    private fun signed(
        template: com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<*>,
        createdAt: Long = 1_700_000_000,
        material: KeyMaterial = groupMaterial,
        signer: KeyMaterial = material,
        signature: String? = null
    ): GroupSignedEvent = GroupSignedEvent.fromEvent(
        event = relayEvent(template, createdAt, material, signer, signature),
        chatRoomId = chatRoomId,
        derivationPath = SharedKeyDerivation.formatPath(path)
    )

    private fun relayEvent(
        template: com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<*>,
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
            kind = template.kind,
            tags = template.tags,
            content = template.content
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = createdAt,
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
