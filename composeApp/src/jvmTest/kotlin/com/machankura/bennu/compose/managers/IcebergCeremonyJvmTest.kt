package com.machankura.bennu.compose.managers

import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.database.model.ChatMessage
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.DkgParticipantMessage
import com.machankura.bennu.compose.database.model.DkgSession
import com.machankura.bennu.compose.database.model.IcebergKeySession
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.types.DkgRitualStage
import com.machankura.bennu.compose.database.model.types.IcebergApprovalStep
import com.machankura.bennu.compose.database.model.types.IcebergRitualStage
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.nostr.dkg.DkgRitualEvents
import com.machankura.bennu.compose.nostr.frost.FrostSigningEvents
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.bitcoin.utils.Either
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * An Iceberg key ceremony between four devices, each with its own database, over the messages the
 * manager queues for the room -- the seam `SubgroupCeremonyInParentRoomTest` uses: what is under test is
 * what each device stores, publishes and refuses, not whether MLS encrypts it.
 *
 * The four hold a 3-of-4 FROST key for the room and have opened an onchain wallet under it, which is
 * what a Lightning wallet needs before it can have a key of its own (decisions D1 and D2).
 */
class IcebergCeremonyJvmTest {
    private val n = 4
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("1ceb".padEnd(62, '0') + "a1")),
        nParticipants = n,
        threshold = 3,
    )
    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val onchainCeremonyId = "onchain".padEnd(64, '0')
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val chain = Chain.Regtest
    private val account = GroupWalletDerivation.account(thresholdPublicKey, chain)

    private val keys = (1..n).map { KeyPair() }
    private val members = keys.map { it.pubKey.toHexKey() }
    private val hostKeys = (1..n).map { "02" + "2${it}".padEnd(64, '0') }

    private val devices = mutableListOf<Device>()

    @AfterTest
    fun close() = devices.forEach { it.db.close() }

    private inner class Device(val index: Int, val db: BennuDatabase, val room: LocalChatRoom) {
        val publicKey: HexKey = members[index]
        val privateKey: ByteArray = keys[index].privKey!!
        val received = mutableSetOf<String>()
        suspend fun ceremony(id: String) = db.icebergKeySessionDao().getSessionById(id)
    }

    private suspend fun device(index: Int, holders: List<HexKey> = members): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())
        members.forEach { member ->
            val eventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = eventId))
        }
        val chatRoom = ChatRoom(id = roomId, userPublicKey = members[index], subject = "Treasury", description = SharedKeyDerivation.describe("Treasury."), mlsGroupState = "00")
        db.chatRoomDao().upsert(chatRoom)
        db.dkgSessionDao().upsert(
            DkgSession(
                id = onchainCeremonyId, chatRoomId = roomId, coordinatorPublicKey = members.first(), userPublicKey = members[index],
                threshold = 3, participantCount = n, stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index], round1Random = "1".repeat(64), round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[index].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() },
                participantPublicKeys = DkgSession.formatParticipants(holders.toSet()),
            )
        )
        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(DkgParticipantMessage(sessionId = onchainCeremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[i]))
        }
        val room = db.chatRoomDao().findChatRoomById(roomId) ?: LocalChatRoom(chatRoom = chatRoom)
        return Device(index, db, room).also { devices += it }
    }

    private suspend fun outbox(from: Device, kinds: Set<Int>) = from.db.marmotInnerEventDao()
        .getByChatRoomAndKinds(chatRoomId = roomId, kinds = kinds.toList())
        .filter { it.publicKey == from.publicKey }
        .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    private fun MarmotInnerEvent.asEvent() = Event(id, publicKey, createdAt.epochSeconds, kind, tags, content, "")

    /** Moves every queued message of [kinds] to every other device until nothing new moves. */
    private suspend fun pump(between: List<Device>, kinds: Set<Int>) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from, kinds).forEach { queued ->
                    if (to.received.add(queued.id)) {
                        moved = true
                        to.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = roomId))
                        when (queued.kind) {
                            in FrostSigningEvents.ALL -> FrostSigningManager.processSigningPayload(to.db, to.room, queued.asEvent(), to.publicKey)
                            in GroupLightningEvents.CEREMONY -> IcebergRitualManager.processCeremonyPayload(to.db, to.room, queued.asEvent(), to.publicKey, to.privateKey)
                        }
                    }
                }
            }
        }
    }

    private suspend fun openOnchainWallet(all: List<Device>) {
        val template = GroupWalletStatementEvent.template(account)
        val session = FrostSigningManager.proposeSigning(all[0].db, all[0].room, all[0].publicKey, template.kind, template.tags, template.content)
        pump(all, FrostSigningEvents.ALL)
        all.drop(1).forEach { FrostSigningManager.approve(it.db, it.room, session.id) }
        pump(all, FrostSigningEvents.ALL)
        all.forEach { assertNotNull(GroupWalletManager.walletFor(it.db, roomId, chain), "device ${it.index} has the onchain wallet") }
    }

    private suspend fun group(): List<Device> = (0 until n).map { device(it) }.also { openOnchainWallet(it) }

    /** Runs the ceremony from [proposer], each other device approving every step when it is asked. */
    private suspend fun runCeremony(all: List<Device>, proposer: Device = all[0], threshold: Int = 2): IcebergKeySession {
        val proposed = IcebergRitualManager.proposeCeremony(proposer.db, proposer.room, proposer.publicKey, proposer.privateKey, threshold, chain)
        repeat(6) {
            pump(all, GroupLightningEvents.CEREMONY)
            all.forEach { d ->
                val session = d.ceremony(proposed.id) ?: return@forEach
                IcebergRitualManager.pendingApproval(d.db, session)?.let { step -> IcebergRitualManager.approve(d.db, d.room, session.id, step, d.privateKey) }
            }
        }
        pump(all, GroupLightningEvents.CEREMONY)
        return proposed
    }

    private fun signsWithStockCosigner(sessions: List<IcebergKeySession>, signers: List<Int>): Boolean {
        val s0 = sessions.first()
        val groupKey = PublicKey.fromHex(s0.groupPublicKey!!)
        val shares = sessions.map { IcebergRitualManager.shareOf(it)!! }
        val byIndex = shares.associateBy { it.toByteArray()[3].toInt() }
        val cosigner = PrivateKey(Random.nextBytes(32).byteVector32())
        val msg = Random.nextBytes(32).byteVector32()
        val sid = Random.nextBytes(32).byteVector32()
        val (aggregateKey, cache) = KeyAggCache.create(listOf(groupKey, cosigner.publicKey()))
        val contributions = signers.map { Iceberg.generateNonce(byIndex.getValue(it), null, sid) }
        val groupNonce = (Iceberg.aggregateNonces(contributions, s0.participantCount, s0.threshold, groupKey) as Either.Right).value
        val (secret, public) = SecretNonce.generate(Random.nextBytes(32).byteVector32(), Either.Left(cosigner), msg, cache, null)
        val session = Session.create((IndividualNonce.aggregate(listOf(groupNonce, public)) as Either.Right).value, msg, cache)
        val cosignerAgg = (IndividualNonce.aggregate(listOf(public)) as Either.Right).value
        val signatureShares = signers.map { (Iceberg.partialSign(byIndex.getValue(it), null, sid, contributions, groupKey, cache, msg, cosignerAgg) as Either.Right).value }
        val groupPartial = (Iceberg.aggregatePartialSignatures(signatureShares, s0.participantCount, s0.threshold) as Either.Right).value
        val sig = (session.aggregateSigs(listOf(groupPartial, (session.sign(secret, cosigner) as Either.Right).value)) as Either.Right).value
        return Crypto.verifySignatureSchnorr(msg, sig, aggregateKey)
    }

    @Test
    fun `four devices make one 2-of-4 key, and it signs`() = runBlocking {
        val all = group()
        val proposed = runCeremony(all)
        val sessions = all.map { assertNotNull(it.ceremony(proposed.id), "device ${it.index} has the ceremony") }
        sessions.forEach { assertEquals(IcebergRitualStage.COMPLETE, it.stage, "device ${it.userPublicKey.take(8)}: ${it.failureReason}") }
        assertEquals(1, sessions.map { it.groupPublicKey }.toSet().size, "every device has the same key")
        assertEquals(1, sessions.map { it.publicShares }.toSet().size)
        sessions.forEach { assertNull(it.root, "the random root is dropped once the share exists") }
        assertTrue(signsWithStockCosigner(sessions, listOf(1, 2, 3)))
        assertTrue(signsWithStockCosigner(sessions, listOf(2, 3, 4)))
        // Each device wrote the ceremony's lines from what it received, ending with the key.
        all.forEach { d ->
            val lines = d.db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }.filter { it.messageType in ChatMessage.ICEBERG_TYPES }
            assertEquals(1, lines.count { it.messageType == ChatMessage.TYPE_ICEBERG_COMPLETE })
            assertEquals(n, lines.count { it.messageType == ChatMessage.TYPE_ICEBERG_COMMITTED })
            assertTrue(ChatMessage.answeredRequests(lines).size == lines.count { it.messageType in ChatMessage.ICEBERG_REQUEST_TYPES }, "every request this device was asked is answered")
        }
    }

    @Test
    fun `nothing of a member's goes out before they approve it`() = runBlocking {
        val all = group()
        val proposed = IcebergRitualManager.proposeCeremony(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, 2, chain)
        pump(all, GroupLightningEvents.CEREMONY)
        val other = all[1]
        assertEquals(IcebergApprovalStep.JOIN, IcebergRitualManager.pendingApproval(other.db, other.ceremony(proposed.id)!!))
        assertTrue(outbox(other, GroupLightningEvents.CEREMONY).isEmpty(), "a member who has not joined has published nothing")

        all.drop(1).forEach { IcebergRitualManager.approve(it.db, it.room, proposed.id, IcebergApprovalStep.JOIN, it.privateKey) }
        pump(all, GroupLightningEvents.CEREMONY)
        assertEquals(setOf(GroupLightningEvents.DKG_ROUND_1), outbox(other, GroupLightningEvents.CEREMONY).map { it.kind }.toSet(), "joining publishes commitments and nothing more")
        assertEquals(IcebergApprovalStep.REVEAL, IcebergRitualManager.pendingApproval(other.db, other.ceremony(proposed.id)!!))
    }

    @Test
    fun `a contribution travels only as ciphertext`() = runBlocking {
        val all = group()
        val proposed = runCeremony(all)
        val reveal = outbox(all[1], setOf(GroupLightningEvents.DKG_ROUND_2)).single()
        val shareHex = all[1].ceremony(proposed.id)!!.share!!
        // No seed a member holds appears in what it sent: only ciphertext addressed to each other member.
        (0 until (shareHex.length - 8) / 64).map { shareHex.substring(8 + 64 * it, 72 + 64 * it) }.forEach { seed ->
            assertTrue(seed !in reveal.content)
        }
        members.filter { it != all[1].publicKey }.forEach { assertTrue(it in reveal.content, "a ciphertext for each other member") }
    }

    @Test
    fun `messages delivered twice, or before the proposal, change nothing`() = runBlocking {
        val all = group()
        val proposed = runCeremony(all)
        val before = all.map { it.ceremony(proposed.id) }
        // Deliver everything to everyone again.
        all.forEach { it.received.clear() }
        pump(all, GroupLightningEvents.CEREMONY)
        assertEquals(before, all.map { it.ceremony(proposed.id) })

        // A fifth reading of the room by a device that hears round one before the proposal: it files
        // the early messages and replays them once the proposal arrives.
        val late = device(3)
        val proposal = outbox(all[0], setOf(GroupLightningEvents.CEREMONY_PROPOSAL)).single()
        val early = all.flatMap { outbox(it, setOf(GroupLightningEvents.DKG_ROUND_1)) }
        early.forEach { late.db.marmotInnerEventDao().upsert(it) }
        early.forEach { IcebergRitualManager.processCeremonyPayload(late.db, late.room, it.asEvent(), late.publicKey, late.privateKey) }
        assertNull(late.ceremony(proposed.id), "nothing is filed before the proposal")
        late.db.marmotInnerEventDao().upsert(proposal)
        IcebergRitualManager.processCeremonyPayload(late.db, late.room, proposal.asEvent(), late.publicKey, late.privateKey)
        assertEquals(n, late.db.icebergKeySessionDao().countMessagesByKind(proposed.id, GroupLightningEvents.DKG_ROUND_1), "the early commitments were replayed")
    }

    @Test
    fun `a proposal naming people who do not hold the onchain key is dropped`() = runBlocking {
        val all = group()
        val outsider = KeyPair().pubKey.toHexKey()
        val tags = (members.drop(1).dropLast(1) + outsider).map { arrayOf("p", it) } + listOf(
            arrayOf("iceberg_session", Random.nextBytes(32).toHex()), arrayOf("threshold", "2"), arrayOf("onchain_dkg", onchainCeremonyId),
        )
        val createdAt = 1_700_000_000L
        val event = Event(EventHasher.hashId(pubKey = members[0], createdAt = createdAt, kind = GroupLightningEvents.CEREMONY_PROPOSAL, tags = tags.toTypedArray(), content = ""), members[0], createdAt, GroupLightningEvents.CEREMONY_PROPOSAL, tags.toTypedArray(), "", "")
        IcebergRitualManager.processCeremonyPayload(all[1].db, all[1].room, event, all[1].publicKey, all[1].privateKey)
        assertTrue(all[1].db.icebergKeySessionDao().getSessionsForChatRoom(roomId).isEmpty())
    }

    @Test
    fun `an undeployable threshold is refused at both ends`() = runBlocking {
        val all = group()
        val refused = runCatching { IcebergRitualManager.proposeCeremony(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, 3, chain) }
        assertTrue(refused.isFailure, "3-of-4 cannot be expressed")
        val tags = members.drop(1).map { arrayOf("p", it) } + listOf(
            arrayOf("iceberg_session", Random.nextBytes(32).toHex()), arrayOf("threshold", "3"), arrayOf("onchain_dkg", onchainCeremonyId),
        )
        val event = Event(EventHasher.hashId(pubKey = members[0], createdAt = 1L, kind = GroupLightningEvents.CEREMONY_PROPOSAL, tags = tags.toTypedArray(), content = ""), members[0], 1L, GroupLightningEvents.CEREMONY_PROPOSAL, tags.toTypedArray(), "", "")
        IcebergRitualManager.processCeremonyPayload(all[1].db, all[1].room, event, all[1].publicKey, all[1].privateKey)
        assertTrue(all[1].db.icebergKeySessionDao().getSessionsForChatRoom(roomId).isEmpty())
    }

    @Test
    fun `there is no ceremony without an onchain wallet`() = runBlocking {
        val lone = (0 until n).map { device(it) }
        val refused = runCatching { IcebergRitualManager.proposeCeremony(lone[0].db, lone[0].room, lone[0].publicKey, lone[0].privateKey, 2, chain) }
        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("onchain wallet"))
    }
}
