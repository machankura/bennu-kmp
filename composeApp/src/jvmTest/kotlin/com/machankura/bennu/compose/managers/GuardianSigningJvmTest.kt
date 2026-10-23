package com.machankura.bennu.compose.managers

import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.DkgParticipantMessage
import com.machankura.bennu.compose.database.model.DkgSession
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.types.DkgRitualStage
import com.machankura.bennu.compose.database.model.types.FrostSigningStage
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.nostr.dkg.DkgRitualEvents
import com.machankura.bennu.compose.nostr.frost.FrostSigningEvents
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A group's guardian keys through the real signing manager, between two of its
 * devices, with the wire held by hand -- the harness `GroupSpendSigningJvmTest`
 * uses.
 *
 * The group here is the *ancestor*. What is under test is what its members'
 * devices agree to: a certificate is only signed when the guardian keys in it
 * are the ones the group grants, whoever proposed it.
 */
class GuardianSigningJvmTest {
    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("6a2d1a".padEnd(62, '0') + "a1")),
        nParticipants = participants,
        threshold = threshold,
    )
    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    private val childKey = PrivateKey(ByteVector32(ByteArray(32) { 0x3c })).publicKey().toString()
    private val childRoomId = SharedKeyDerivation.marmotGroupId(childKey)

    private val devices = mutableListOf<Device>()

    @AfterTest
    fun closeDatabases() = devices.forEach { it.db.close() }

    private inner class Device(val publicKey: HexKey, val db: BennuDatabase, val room: LocalChatRoom) {
        val received = mutableSetOf<String>()

        suspend fun store(queued: Queued) = db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = queued.id, publicKey = queued.publicKey, kind = queued.kind, createdAt = queued.createdAt,
                tags = queued.tags, content = queued.content, chatRoomId = roomId,
            )
        )

        suspend fun session(id: String) = db.frostSigningSessionDao().getSessionById(id)
    }

    private suspend fun device(publicKey: HexKey, signerIndex: Int): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())
        members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(
            id = roomId, userPublicKey = publicKey, subject = "Council",
            description = SharedKeyDerivation.describe("Council."), mlsGroupState = "00",
        )
        db.chatRoomDao().upsert(chatRoom)
        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId, chatRoomId = roomId, coordinatorPublicKey = members.first(), userPublicKey = publicKey,
                threshold = threshold, participantCount = participants, stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[signerIndex], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[signerIndex].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
            )
        )
        members.forEachIndexed { index, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[index])
            )
        }
        val localChatRoom = db.chatRoomDao().findChatRoomById(roomId) ?: LocalChatRoom(chatRoom = chatRoom)
        return Device(publicKey, db, localChatRoom).also { devices += it }
    }

    private data class Queued(
        val id: HexKey, val publicKey: HexKey, val kind: Int,
        val tags: Array<Array<String>>, val content: String, val createdAt: Instant,
    ) {
        fun toEvent() = Event(id, publicKey, createdAt.epochSeconds, kind, tags, content, "")
    }

    private suspend fun outbox(device: Device): List<Queued> =
        device.db.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = roomId, kinds = FrostSigningEvents.ALL.toList())
            .map { Queued(it.id, it.publicKey, it.kind, it.tags, it.content, it.createdAt) }
            .filter { it.publicKey == device.publicKey }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    private suspend fun deliver(to: Device, queued: Queued) {
        if (!to.received.add(queued.id)) return
        to.store(queued)
        FrostSigningManager.processSigningPayload(to.db, to.room, queued.toEvent(), to.publicKey)
    }

    private suspend fun pump(vararg between: Device) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from).forEach { queued ->
                    if (queued.id !in to.received) moved = true
                    deliver(to, queued)
                }
            }
        }
    }

    private fun certificateTags(guardians: List<GuardianDerivation.Guardian>) = SubgroupBirthCertificateEvent.assembleTags(
        subgroupChatRoomId = childRoomId,
        parentChatRoomId = roomId,
        thresholdPublicKey = childKey,
        adminPublicKeys = members,
        name = "Translation team",
        guardians = guardians,
    )

    private suspend fun proposeCertificate(proposer: Device, guardians: List<GuardianDerivation.Guardian>) =
        FrostSigningManager.proposeSigning(
            proposer.db, proposer.room, proposer.publicKey, SubgroupBirthCertificateEvent.KIND, certificateTags(guardians), childRoomId,
        )

    // ---- The certificate's guardian keys --------------------------------

    @Test
    fun `a certificate granting the group's own key is signed, and carries it`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val granted = GuardianDerivation.grant(thresholdPublicKey, roomId, emptyList(), childRoomId)

        val session = proposeCertificate(proposer, granted)
        pump(proposer, other)
        assertNotNull(other.session(session.id), "the other member is asked")
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        listOf(proposer, other).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val certificate = assertNotNull(SubgroupManager.certificateFor(device.db, childRoomId, roomId)).toEvent()
            assertEquals(granted, SubgroupBirthCertificateEvent.parseGuardians(certificate.tags))
        }
    }

    @Test
    fun `a certificate granting any other key is never put to the member`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val coordinators = PrivateKey(ByteVector32(ByteArray(32) { 0x66 })).publicKey().toString()

        // The coordinator's own key dressed as the group's guardian key.
        val session = proposeCertificate(proposer, GuardianDerivation.grant(coordinators, roomId, emptyList(), childRoomId))
        pump(proposer, other)

        assertNull(other.session(session.id), "refused at acceptance, so nothing of the member's is ever published for it")
    }
}
