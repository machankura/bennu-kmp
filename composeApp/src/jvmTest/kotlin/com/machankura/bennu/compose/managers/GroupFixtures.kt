package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GroupKeyState
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
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
import kotlin.time.Instant

/**
 * Groups with real keys, for the tests that need a group's signature on
 * something: a trusted-dealer key per group, its room id, and a 2-of-3 FROST
 * signature as the room. The shape `SubgroupManagerJvmTest` builds inline, kept
 * in one place for the member-wallet tests, which need a parent, a group and a
 * certificate between them in nearly every case.
 */
internal object GroupFixtures {
    private const val PARTICIPANTS = 3
    private const val THRESHOLD = 2

    val path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH

    fun keyMaterial(secret: String): KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32(secret.padStart(64, '0'))),
        nParticipants = PARTICIPANTS,
        threshold = THRESHOLD,
    )

    fun thresholdKeyOf(material: KeyMaterial): HexKey = material.thresholdPublicKey.value.toHex()

    fun roomOf(material: KeyMaterial): String = SharedKeyDerivation.marmotGroupId(thresholdKeyOf(material), path)

    /** [kind] over [tags] and [content], signed by [material]'s quorum as its room. */
    fun signedAsRoom(material: KeyMaterial, kind: Int, tags: Array<Array<String>>, content: String, createdAt: Long = 1_700_000_000): Event {
        val pubKey = roomOf(material)
        val id = EventHasher.hashId(pubKey = pubKey, createdAt = createdAt, kind = kind, tags = tags, content = content)
        return Event(id = id, pubKey = pubKey, createdAt = createdAt, kind = kind, tags = tags, content = content, sig = signature(material, id))
    }

    /**
     * [parent]'s certificate for [child], granting [guardians] under [policy] --
     * the grant is the caller's, so a test can hand over a wrong one.
     */
    fun birthCertificate(
        parent: KeyMaterial,
        child: KeyMaterial,
        guardians: List<GuardianDerivation.Guardian>,
        policy: SubgroupWalletPolicy? = if (guardians.isEmpty()) SubgroupWalletPolicy.UNGUARDED else SubgroupWalletPolicy.GUARDED,
        admins: List<HexKey> = listOf("a".repeat(64)),
    ): Event = signedAsRoom(
        material = parent,
        kind = SubgroupBirthCertificateEvent.KIND,
        tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = roomOf(child),
            parentChatRoomId = roomOf(parent),
            thresholdPublicKey = thresholdKeyOf(child),
            adminPublicKeys = admins,
            name = "#Child",
            path = path,
            guardians = guardians,
            walletPolicy = policy,
        ),
        content = roomOf(child),
    )

    /** [parent]'s guarded certificate for [child], with exactly the grant a parent with no parent makes. */
    fun guardedCertificate(parent: KeyMaterial, child: KeyMaterial): Event =
        birthCertificate(parent, child, GuardianDerivation.grant(thresholdKeyOf(parent), roomOf(parent), emptyList(), roomOf(child)))

    /** [material]'s verified key state on this device, carrying [certificate] when it is somebody's subgroup. */
    suspend fun fileKeyState(db: BennuDatabase, material: KeyMaterial, certificate: Event? = null) {
        seedRoom(db, roomOf(material))
        db.groupKeyStateDao().upsert(
            GroupKeyState(
                chatRoomId = roomOf(material),
                dkgSessionId = "ceremony-" + roomOf(material).take(8),
                thresholdPublicKey = thresholdKeyOf(material),
                derivationPath = SharedKeyDerivation.formatPath(path),
                announcedBy = roomOf(material),
                announcedAt = Instant.fromEpochSeconds(1_700_000_000),
                parentChatRoomId = certificate?.let { SubgroupBirthCertificateEvent.parseParentChatRoomId(it.tags) },
                birthCertificateJson = certificate?.toJson(),
            )
        )
    }

    /** The device's own profile, which every room row names. */
    val owner: HexKey = "0e".repeat(32)

    suspend fun seedProfile(db: BennuDatabase, publicKey: HexKey, name: String = "member") {
        if (db.profileDao().getProfileByPublicKey(publicKey) != null) return
        val nostrEventId = publicKey.take(63) + "f"
        db.nostrEventDao().upsert(
            NostrEvent(id = nostrEventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = nostrEventId))
    }

    /** A room row for [chatRoomId], if there is none: what a key state, a signed event or a session hangs off. */
    suspend fun seedRoom(db: BennuDatabase, chatRoomId: String, subject: String? = null) {
        seedProfile(db, owner)
        if (db.chatRoomDao().findChatRoomById(chatRoomId) != null) return
        db.chatRoomDao().upsert(ChatRoom(id = chatRoomId, userPublicKey = owner, subject = subject, description = null, mlsGroupState = null))
    }

    private fun signature(material: KeyMaterial, eventId: String): String {
        val cache = SharedKeyDerivation.derive(thresholdKeyOf(material), path).cache
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = material.secretShares[signerId],
                publicShare = material.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null,
            )
        }
        val session = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { material.publicShares[it] },
            nParticipants = PARTICIPANTS,
            threshold = THRESHOLD,
            tweakCache = cache,
            message = message,
        )
        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, material.secretShares[signerId], signerId.toUInt()).right!!
        }
        return session.aggregateSigs(partials).right!!.toByteArray().toHex()
    }
}
