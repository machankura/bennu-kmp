package to.curare.compose.nostr.subgroup

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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import to.curare.compose.extensions.toHex
import to.curare.compose.managers.SharedKeyDerivation

/**
 * What a birth certificate is allowed to convince a member of.
 *
 * A certificate is the whole of what a subgroup relationship is, so everything
 * downstream of it -- a `GroupKeyState` carrying a parent, a subgroup list, a
 * lineage shown to a user -- is worth exactly what `certifies` is worth. It has
 * a database nowhere in it on purpose: the deciding is pure, and pure is what
 * can be exercised exhaustively.
 *
 * Two halves again, and they are the same two `GroupKeyStateTest` names. A
 * certificate has to be **true** -- the id it certifies rederives from the key
 * and path it carries, so it cannot be pointed at a room that key did not make.
 * And it has to be the **parent's** -- signed by the room it names as parent,
 * which no single member of that room can do.
 *
 * The last test is the odd one and the most important to keep. It asserts a
 * certificate still verifies when its name and admin set have nothing to do with
 * the room's current ones, because those are the *founding* roster and the world
 * moves. A `certifies` that compared them would start rejecting valid
 * certificates the first time somebody joined a subgroup, and the rejection
 * would look exactly like a forgery.
 */
class SubgroupBirthCertificateEventTest {
    private val participants = 3
    private val threshold = 2

    /** The parent group: the quorum that does the certifying. */
    private val parentMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** The child: its own ceremony, its own key, its own room. */
    private val childMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("2bada550000000000000000000000000000000000000000000000000000000b2")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** A third group entirely, for the certificates signed by the wrong room. */
    private val strangerMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("3decade0000000000000000000000000000000000000000000000000000000c3")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    private val childKey = childMaterial.thresholdPublicKey.value.toHex()
    private val strangerKey = strangerMaterial.thresholdPublicKey.value.toHex()

    private val parentChatRoomId =
        SharedKeyDerivation.marmotGroupId(parentMaterial.thresholdPublicKey.value.toHex(), path)
    private val subgroupChatRoomId = SharedKeyDerivation.marmotGroupId(childKey, path)

    private val admins = listOf("a".repeat(64), "b".repeat(64), "c".repeat(64))

    private val name = "Translation team"

    @Test
    fun `a certificate the parent signed for the child it names verifies`() {
        assertTrue(
            SubgroupBirthCertificateEvent.certifies(
                event = certificate(),
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate whose content and address disagree is refused`() {
        // The content is the claim and the `d` tag is the address. One pointing
        // somewhere the other does not is not a thing to guess the meaning of --
        // and guessing the content would let a certificate be filed against a
        // child it does not claim.
        val misaddressed = certificate(
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = parentChatRoomId,
                parentChatRoomId = parentChatRoomId,
                thresholdPublicKey = childKey,
                adminPublicKeys = admins,
                name = name,
                path = path
            )
        )

        assertFalse(
            SubgroupBirthCertificateEvent.certifies(
                event = misaddressed,
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate whose id does not derive from the key it names is refused`() {
        // The attack: certify a room id the parent's admins were shown alongside
        // a key that does not make it, so that what they approved and what they
        // signed are two different groups.
        val mismatched = certificate(
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId,
                thresholdPublicKey = strangerKey,
                adminPublicKeys = admins,
                name = name,
                path = path
            )
        )

        assertFalse(
            SubgroupBirthCertificateEvent.certifies(
                event = mismatched,
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate naming the right key at the wrong path is refused`() {
        // The path is half the derivation, so a certificate at m/9420/0/1 names
        // a room that is not the one being certified just as surely as a wrong
        // key would.
        val elsewhere = certificate(
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId,
                thresholdPublicKey = childKey,
                adminPublicKeys = admins,
                name = name,
                path = listOf(9420L, 0L, 1L)
            )
        )

        assertFalse(
            SubgroupBirthCertificateEvent.certifies(
                event = elsewhere,
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate signed by another group is refused`() {
        // A real quorum and a real signature, by a group with no standing to say
        // anything about this child. Lineage is only ever evidence about the
        // parent that signed it.
        val stranger = certificate(signer = strangerMaterial, author = strangerMaterial)

        assertFalse(
            SubgroupBirthCertificateEvent.certifies(
                event = stranger,
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate authored by the parent and signed by somebody else is refused`() {
        // Says the right things and is authored by the right room, and the
        // signature is over that id by a key that is not the room's. This is the
        // shape a member forging a lineage would produce.
        assertFalse(
            SubgroupBirthCertificateEvent.certifies(
                event = certificate(signer = strangerMaterial),
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate whose signature is not a signature is refused`() {
        // Everything off the wire is allowed to be nonsense, and nonsense means
        // no rather than an exception.
        listOf("f".repeat(128), "not a signature", "").forEach { rubbish ->
            assertFalse(
                SubgroupBirthCertificateEvent.certifies(
                    event = certificate(signature = rubbish),
                    subgroupChatRoomId = subgroupChatRoomId,
                    parentChatRoomId = parentChatRoomId
                ),
                "a certificate signed with \"$rubbish\" was accepted"
            )
        }
    }

    @Test
    fun `a certificate whose key is not a point on the curve is refused`() {
        // 66 characters of hex, so it passes the shape check and fails the walk.
        // `marmotGroupId` throws on it and the catch is what turns that into a no.
        val notAKey = certificate(
            tags = arrayOf(
                arrayOf("d", subgroupChatRoomId),
                arrayOf("parent_group", parentChatRoomId),
                arrayOf("subgroup_key", "0".repeat(66)),
                arrayOf("frost_path", SharedKeyDerivation.formatPath(path)),
                arrayOf("name", name)
            )
        )

        assertFalse(
            SubgroupBirthCertificateEvent.certifies(
                event = notAKey,
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `a certificate carrying no parent, no key or no path is refused`() {
        listOf("parent_group", "subgroup_key", "frost_path").forEach { dropped ->
            val incomplete = certificate(
                tags = SubgroupBirthCertificateEvent.assembleTags(
                    subgroupChatRoomId = subgroupChatRoomId,
                    parentChatRoomId = parentChatRoomId,
                    thresholdPublicKey = childKey,
                    adminPublicKeys = admins,
                    name = name,
                    path = path
                ).filterNot { it[0] == dropped }.toTypedArray()
            )

            assertFalse(
                SubgroupBirthCertificateEvent.certifies(
                    event = incomplete,
                    subgroupChatRoomId = subgroupChatRoomId,
                    parentChatRoomId = parentChatRoomId
                ),
                "a certificate with no $dropped tag was accepted"
            )
        }
    }

    @Test
    fun `a certificate still verifies once the subgroup has been renamed and re-staffed`() {
        // The one that has to pass. The name and the `p` tags are the founding
        // roster: a certificate is signed once and members join and leave
        // afterwards. This test exists to fail loudly the day somebody adds the
        // roster check that looks obviously missing -- which would reject every
        // certificate for a subgroup that has grown, and would look like a
        // forgery rather than like a rule.
        val founding = certificate(
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId,
                thresholdPublicKey = childKey,
                adminPublicKeys = listOf("d".repeat(64)),
                name = "What it was called in 2026",
                path = path
            )
        )

        assertTrue(
            SubgroupBirthCertificateEvent.certifies(
                event = founding,
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId
            )
        )
    }

    @Test
    fun `the tags a certificate is proposed on read back as they were written`() {
        val tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = subgroupChatRoomId,
            parentChatRoomId = parentChatRoomId,
            thresholdPublicKey = childKey,
            adminPublicKeys = admins,
            name = name,
            path = path
        )

        assertEquals(subgroupChatRoomId, SubgroupBirthCertificateEvent.parseSubgroupChatRoomId(tags))
        assertEquals(parentChatRoomId, SubgroupBirthCertificateEvent.parseParentChatRoomId(tags))
        assertEquals(childKey, SubgroupBirthCertificateEvent.parseThresholdPublicKey(tags))
        assertEquals(path, SubgroupBirthCertificateEvent.parsePath(tags))
        assertEquals(admins, SubgroupBirthCertificateEvent.parseAdminPublicKeys(tags))
        assertEquals(name, SubgroupBirthCertificateEvent.parseName(tags))
    }

    @Test
    fun `a duplicated admin is written once`() {
        // The p-tags are a roster, and `n` is not read off them by anything --
        // but a list that says somebody twice reads as two people to a human,
        // which is the only audience they have.
        val tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = subgroupChatRoomId,
            parentChatRoomId = parentChatRoomId,
            thresholdPublicKey = childKey,
            adminPublicKeys = admins + admins.first(),
            name = name,
            path = path
        )

        assertEquals(admins, SubgroupBirthCertificateEvent.parseAdminPublicKeys(tags))
    }

    @Test
    fun `a threshold key is only read out of a tag that carries one`() {
        // Shape only, and borrowed from GroupKeyStateEvent so that the two
        // readers of a threshold key cannot disagree about what one is.
        assertNull(SubgroupBirthCertificateEvent.parseThresholdPublicKey(arrayOf(arrayOf("subgroup_key", ""))))
        assertNull(SubgroupBirthCertificateEvent.parseThresholdPublicKey(arrayOf(arrayOf("subgroup_key", subgroupChatRoomId))))
        assertNull(SubgroupBirthCertificateEvent.parseThresholdPublicKey(arrayOf(arrayOf("subgroup_key", "z".repeat(66)))))
        assertNull(SubgroupBirthCertificateEvent.parseThresholdPublicKey(arrayOf(arrayOf("subgroup_key"))))
    }

    /**
     * A certificate as the app produces one: the caller's fields under the
     * parent room's key, with the id hashed over them and the group's own
     * signature on it.
     *
     * [author] decides whose room the event claims to be from and [signer] whose
     * quorum actually signs, so the two can be pulled apart -- which is the only
     * way to test that both are checked. [signature] replaces the real one
     * outright, for the cases where what arrives is not a signature at all.
     */
    private fun certificate(
        content: String = subgroupChatRoomId,
        tags: Array<Array<String>> = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = subgroupChatRoomId,
            parentChatRoomId = parentChatRoomId,
            thresholdPublicKey = childKey,
            adminPublicKeys = admins,
            name = name,
            path = path
        ),
        createdAt: Long = 1_700_000_000,
        author: KeyMaterial = parentMaterial,
        signer: KeyMaterial = author,
        signature: String? = null
    ): Event {
        val groupPubKey = SharedKeyDerivation
            .derive(author.thresholdPublicKey.value.toHex(), path)
            .hex

        val id = EventHasher.hashId(
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = SubgroupBirthCertificateEvent.KIND,
            tags = tags,
            content = content
        )

        return Event(
            id = id,
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = SubgroupBirthCertificateEvent.KIND,
            tags = tags,
            content = content,
            sig = signature ?: groupSignature(signer, id)
        )
    }

    /**
     * A real FROST signature by [material]'s quorum over [eventId], through the
     * same shape `FrostSigningManager.advance` runs -- nonces, a signer set,
     * partial signatures, an aggregate.
     *
     * Assembled any other way it would not be evidence about the signatures this
     * app actually produces.
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
