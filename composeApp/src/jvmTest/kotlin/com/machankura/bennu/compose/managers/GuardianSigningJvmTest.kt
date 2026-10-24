package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.database.model.GroupKeyState
import com.machankura.bennu.compose.nostr.subgroup.SubgroupWalletPolicy
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
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import kotlin.test.assertTrue
import com.machankura.bennu.compose.wallet.GroupSpend
import com.machankura.bennu.compose.wallet.GroupSpendBuilder
import com.machankura.bennu.compose.wallet.GroupWalletState
import com.machankura.bennu.compose.database.model.types.FrostSigningSubject
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import kotlin.test.assertIs

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
    fun `a certificate granting the group's own key is signed, and carries it`(): Unit = runBlocking {
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
    fun `a certificate granting any other key is never put to the member`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val coordinators = PrivateKey(ByteVector32(ByteArray(32) { 0x66 })).publicKey().toString()

        // The coordinator's own key dressed as the group's guardian key.
        val session = proposeCertificate(proposer, GuardianDerivation.grant(coordinators, roomId, emptyList(), childRoomId))
        pump(proposer, other)

        assertNull(other.session(session.id), "refused at acceptance, so nothing of the member's is ever published for it")
    }

    // ---- The parent's decision: docs/subgroup-wallet-policy.md ------------

    /** A parent of this room, as a key whose certificate this test files without signing. */
    private val decidingParentKey = PrivateKey(ByteVector32(ByteArray(32) { 0x4d })).publicKey().toString()
    private val decidingParentRoomId = SharedKeyDerivation.marmotGroupId(decidingParentKey)
    private val grantedToRoom = GuardianDerivation.grant(decidingParentKey, decidingParentRoomId, emptyList(), roomId)

    /**
     * This room as a subgroup its parent made under [policy], filed on [device]
     * the way a verified key state leaves it. The policy is read off the
     * certificate's tags; whether the parent signed it was `stateFrom`'s
     * question, asked before the row was written.
     */
    private suspend fun madeUnder(device: Device, policy: SubgroupWalletPolicy?): Event {
        val tags = SubgroupBirthCertificateEvent.assembleTags(
            subgroupChatRoomId = roomId,
            parentChatRoomId = decidingParentRoomId,
            thresholdPublicKey = thresholdPublicKey,
            adminPublicKeys = members,
            name = "Council",
            guardians = if (policy == SubgroupWalletPolicy.UNGUARDED) emptyList() else grantedToRoom,
            walletPolicy = policy,
        )
        val certificate = Event("d".repeat(64), decidingParentRoomId, 1_700_000_000, SubgroupBirthCertificateEvent.KIND, tags, roomId, "f".repeat(128))
        device.db.groupKeyStateDao().upsert(
            GroupKeyState(
                chatRoomId = roomId,
                dkgSessionId = ceremonyId,
                thresholdPublicKey = thresholdPublicKey,
                derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH),
                announcedBy = roomId,
                announcedAt = Instant.fromEpochSeconds(1_700_000_000),
                parentChatRoomId = decidingParentRoomId,
                birthCertificateJson = certificate.toJson(),
            )
        )
        return certificate
    }

    private suspend fun proposeStatement(proposer: Device, guarded: Boolean, certificate: Event) = run {
        val template = if (guarded) {
            GroupWalletStatementEvent.template(
                GroupWalletDerivation.account(thresholdPublicKey, Chain.Regtest, guardians = grantedToRoom),
                certificate,
            )
        } else {
            GroupWalletStatementEvent.template(GroupWalletDerivation.account(thresholdPublicKey, Chain.Regtest))
        }
        FrostSigningManager.proposeSigning(proposer.db, proposer.room, proposer.publicKey, template.kind, template.tags, template.content)
    }

    @Test
    fun `a plain wallet is never put to a member whose parent decided guarded`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        madeUnder(proposer, SubgroupWalletPolicy.GUARDED)
        val certificate = madeUnder(other, SubgroupWalletPolicy.GUARDED)

        // What an older build, or a skipped screen, would propose.
        val plain = proposeStatement(proposer, guarded = false, certificate)
        val guarded = proposeStatement(proposer, guarded = true, certificate)
        pump(proposer, other)

        assertNull(other.session(plain.id), "refused before it is shown")
        assertNotNull(other.session(guarded.id), "the guarded one is asked")
    }

    @Test
    fun `a guarded wallet is never put to a member whose parent decided unguarded`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        madeUnder(proposer, SubgroupWalletPolicy.UNGUARDED)
        madeUnder(other, SubgroupWalletPolicy.UNGUARDED)

        // A certificate granting keys, as a modified client might carry; the
        // room's own decided none.
        val guarded = proposeStatement(proposer, guarded = true, madeUnder(device(members[2], signerIndex = 2), SubgroupWalletPolicy.GUARDED))
        val plain = proposeStatement(proposer, guarded = false, madeUnder(proposer, SubgroupWalletPolicy.UNGUARDED))
        pump(proposer, other)

        assertNull(other.session(guarded.id))
        assertNotNull(other.session(plain.id))
    }

    @Test
    fun `an undecided room is asked about either, as before`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        madeUnder(proposer, null)
        val certificate = madeUnder(other, null)

        val plain = proposeStatement(proposer, guarded = false, certificate)
        val guarded = proposeStatement(proposer, guarded = true, certificate)
        pump(proposer, other)

        assertNotNull(other.session(plain.id))
        assertNotNull(other.session(guarded.id))
    }

    // ---- Telling an ancestor about a guarded wallet -----------------------

    /** The child as a group of its own, so its room can sign its statement. */
    private val childMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("c41d".padEnd(62, '0') + "b2")),
        nParticipants = participants,
        threshold = threshold,
    )
    private val guardedChildKey = childMaterial.thresholdPublicKey.value.toHex()
    private val guardedChildRoomId = SharedKeyDerivation.marmotGroupId(guardedChildKey)

    /** [material]'s room signing [id], as a quorum of its trusted-dealer shares. */
    private fun roomSignature(material: KeyMaterial, id: String): String {
        val cache = SharedKeyDerivation.derive(material.thresholdPublicKey.value.toHex()).cache
        val message = ByteVector(id.hexToByteArray())
        val signerIds = listOf(0, 1)
        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("b".repeat(63) + "${signerId + 1}"),
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
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message,
        )
        val partials = signerIds.mapIndexed { position, signerId ->
            session.sign(nonces[position].first, material.secretShares[signerId], signerId.toUInt()).right!!
        }
        return session.aggregateSigs(partials).right!!.toByteArray().toHex()
    }

    private fun signedBy(material: KeyMaterial, kind: Int, tags: Array<Array<String>>, content: String, createdAt: Long = 1_700_000_000): Event {
        val author = SharedKeyDerivation.marmotGroupId(material.thresholdPublicKey.value.toHex())
        val id = EventHasher.hashId(author, createdAt, kind, tags, content)
        return Event(id, author, createdAt, kind, tags, content, roomSignature(material, id))
    }

    /** The ancestor certifies the child with [guardians], and the child signs a guarded statement under them. */
    private fun guardedStatement(
        guardians: List<GuardianDerivation.Guardian> = GuardianDerivation.grant(thresholdPublicKey, roomId, emptyList(), guardedChildRoomId),
        createdAt: Long = 1_700_000_000,
    ): Event {
        val certificate = signedBy(
            keyMaterial, SubgroupBirthCertificateEvent.KIND,
            SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = guardedChildRoomId, parentChatRoomId = roomId, thresholdPublicKey = guardedChildKey,
                adminPublicKeys = members, name = "Translation team", guardians = guardians,
            ),
            guardedChildRoomId,
        )
        val account = GroupWalletDerivation.account(guardedChildKey, Chain.Regtest, guardians = guardians)
        val template = GroupWalletStatementEvent.template(account, certificate, createdAt)
        return signedBy(childMaterial, template.kind, template.tags, template.content, createdAt)
    }

    private suspend fun tell(device: Device, statement: Event, from: HexKey = members[2], createdAt: Long = 1_700_000_100) {
        val content = statement.toJson()
        device.db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = EventHasher.hashId(from, createdAt, GuardedWalletNoticeEvent.KIND, emptyArray(), content),
                publicKey = from, kind = GuardedWalletNoticeEvent.KIND, createdAt = Instant.fromEpochSeconds(createdAt),
                tags = emptyArray(), content = content, chatRoomId = roomId,
            )
        )
    }

    @Test
    fun `an ancestor believes a statement naming a key its own walk reaches, and nothing else`() {
        val statement = guardedStatement()

        val wallet = assertNotNull(GuardianManager.verify(statement, roomId, thresholdPublicKey))
        assertEquals(guardedChildRoomId, wallet.descendantChatRoomId)
        assertEquals(listOf(guardedChildRoomId), wallet.lineage)
        assertEquals("Translation team", wallet.name)
        assertTrue(wallet.account.isGuarded)

        val strangerKey = PrivateKey(ByteVector32(ByteArray(32) { 0x55 })).publicKey().toString()
        val refusals = mapOf(
            "a group that is not among its guardians" to GuardianManager.verify(statement, guardedChildRoomId, guardedChildKey),
            "a key the ancestor's walk does not reach" to GuardianManager.verify(
                guardedStatement(GuardianDerivation.grant(strangerKey, roomId, emptyList(), guardedChildRoomId)), roomId, thresholdPublicKey,
            ),
            "a statement the child's room did not sign" to GuardianManager.verify(Event(statement.id, statement.pubKey, statement.createdAt, statement.kind, statement.tags, statement.content, "0".repeat(128)), roomId, thresholdPublicKey),
            "a plain statement" to run {
                val plain = GroupWalletStatementEvent.template(GroupWalletDerivation.account(guardedChildKey, Chain.Regtest))
                GuardianManager.verify(signedBy(childMaterial, plain.kind, plain.tags, plain.content), roomId, thresholdPublicKey)
            },
        )
        refusals.forEach { (name, verified) -> assertNull(verified, name) }
    }

    @Test
    fun `a notice in the ancestor's room is a guarded wallet it holds, under its first guardians`(): Unit = runBlocking {
        val ancestor = device(members[0], signerIndex = 0)
        val first = guardedStatement(createdAt = 1_700_000_000)
        tell(ancestor, first)
        // A later statement for the same account, naming other guardians, cannot
        // move what the ancestor watches -- the earliest decides.
        val second = guardedStatement(
            GuardianDerivation.grant(thresholdPublicKey, roomId, emptyList(), guardedChildRoomId).map { it.copy(delay = 13) },
            createdAt = 1_700_000_500,
        )
        tell(ancestor, second, createdAt = 1_700_000_600)
        // And nonsense is read past, not believed.
        ancestor.db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(id = "f".repeat(64), publicKey = members[2], kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1), tags = emptyArray(), content = "not an event", chatRoomId = roomId)
        )

        val held = GuardianManager.guardedWalletsOf(ancestor.db, roomId)
        assertEquals(listOf(first.id), held.map { it.statement.id })
        assertEquals(
            "Shared the guarded wallet of Translation team with this group.",
            GuardianManager.describeNotice(ancestor.db, roomId, Event("1".repeat(64), members[2], 1, GuardedWalletNoticeEvent.KIND, emptyArray(), first.toJson(), ""))
        )
    }

    @Test
    fun `a member in both rooms carries the child's statement up, once`(): Unit = runBlocking {
        val member = device(members[0], signerIndex = 0)
        // This device is also in the child's room, and holds its signed statement there.
        member.db.chatRoomDao().upsert(ChatRoom(id = guardedChildRoomId, userPublicKey = member.publicKey, subject = "Translation team", description = null, mlsGroupState = "00"))
        member.db.groupKeyStateDao().upsert(
            com.machankura.bennu.compose.database.model.GroupKeyState(
                chatRoomId = guardedChildRoomId, dkgSessionId = "child-ceremony", thresholdPublicKey = guardedChildKey,
                derivationPath = SharedKeyDerivation.formatPath(), announcedBy = guardedChildRoomId,
                announcedAt = Instant.fromEpochSeconds(1_700_000_000),
            )
        )
        val statement = guardedStatement()
        member.db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(statement, chatRoomId = guardedChildRoomId))

        val shareable = GuardianManager.shareableFrom(member.db, guardedChildRoomId, Chain.Regtest).single()
        assertEquals(statement.id, shareable.statement.id)
        assertEquals(listOf(roomId), shareable.rooms.map { it.chatRoom.id })

        assertEquals(1, GuardianManager.share(member.db, shareable.statement, shareable.rooms, member.publicKey))
        val queued = member.db.marmotInnerEventDao().getByChatRoomAndKinds(roomId, listOf(GuardedWalletNoticeEvent.KIND)).single()
        assertEquals(member.publicKey, queued.publicKey)
        assertNull(queued.marmotGroupEventId, "queued for the outbound pipeline, not yet sent")

        // Told is told: nothing left to share, and the ancestor's own device reads it back.
        assertEquals(emptyList(), GuardianManager.shareableFrom(member.db, guardedChildRoomId, Chain.Regtest))
        assertEquals(listOf(statement.id), GuardianManager.guardedWalletsOf(member.db, roomId).map { it.statement.id })
    }

    @Test
    fun `a grandchild's statement told to its parent and grandparent at once reaches both`(): Unit = runBlocking {
        val member = device(members[0], signerIndex = 0)
        val grandparentRoomId = "9".repeat(64)
        member.db.chatRoomDao().upsert(ChatRoom(id = grandparentRoomId, userPublicKey = member.publicKey, subject = "Assembly", description = null, mlsGroupState = "00"))
        val rooms = listOf(roomId, grandparentRoomId).map { assertNotNull(member.db.chatRoomDao().findChatRoomById(it)) }

        // One statement, one sharer, one second: only the room tells the two notices apart.
        assertEquals(2, GuardianManager.share(member.db, guardedStatement(), rooms, member.publicKey, createdAt = 1_700_000_100))

        val told = listOf(roomId, grandparentRoomId).associateWith { room ->
            member.db.marmotInnerEventDao().getByChatRoomAndKinds(room, listOf(GuardedWalletNoticeEvent.KIND)).single()
        }
        told.forEach { (room, notice) -> assertEquals(listOf(listOf("h", room)), notice.tags.map { it.toList() }) }
        assertEquals(2, told.values.map { it.id }.toSet().size)
    }

    // ---- Spending from a guarded wallet ------------------------------------

    private val chain = Chain.Regtest
    private val elsewhere = ByteVector(Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())))

    /** Somebody else's money paying the child's first guarded receive address. */
    private fun fundingOf(guarded: GroupWalletDerivation.Account) = Transaction(
        2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x61 })), 0), ByteVector.empty, 0xffffffffL)),
        listOf(TxOut(Satoshi(50_000), GroupWalletDerivation.derive(guarded, GroupWalletDerivation.RECEIVE, 0).pubkeyScript)), 0,
    )

    /** The child's wallet as Electrum would report it holding [funding], confirmed at [height], with the tip at [tip]. */
    private fun fundedState(guarded: GroupWalletDerivation.Account, funding: Transaction, height: Int, tip: Int): GroupWalletState {
        val meta = WalletState.AddressMeta.Derived(0)
        val address = GroupWalletDerivation.derive(guarded, GroupWalletDerivation.RECEIVE, 0).address(chain)
        val receive = WalletState(mapOf(address to WalletState.AddressState(meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(funding.txid, 0, height.toLong(), funding, meta)))))
        val change = WalletState((0 until 3).associate { index ->
            GroupWalletDerivation.derive(guarded, GroupWalletDerivation.CHANGE, index.toLong()).address(chain) to
                WalletState.AddressState(WalletState.AddressMeta.Derived(index), alreadyUsed = false, utxos = emptyList())
        })
        return GroupWalletState(guarded, receive, change, tipHeight = tip)
    }

    @Test
    fun `the builder waits out the leaf's lock, and puts it on every input`() {
        val guarded = assertNotNull(GuardianManager.verify(guardedStatement(), roomId, thresholdPublicKey))
        val funding = fundingOf(guarded.account)
        val address = fr.acinq.bitcoin.Bitcoin.addressFromPublicKeyScript(chain.chainHash, elsewhere.toByteArray()).right!!

        // Eleven confirmations: the owner could spend it, the guardian cannot yet.
        val young = fundedState(guarded.account, funding, height = 100, tip = 110)
        assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.sendAll(young, address, 2, emptySet()))
        assertEquals(GroupSpendBuilder.Result.NothingToSpend, GroupSpendBuilder.sendAll(young, address, 2, emptySet(), guarded.spender()))

        val old = fundedState(guarded.account, funding, height = 100, tip = 111)
        val built = assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.sendAll(old, address, 2, emptySet(), guarded.spender()))
        assertEquals(listOf(12L), built.psbt.global.tx.txIn.map { it.sequence })

        // What the builder charged for is what a signer weighs it at.
        val checked = assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, guarded.account, guarded.spender(thresholdPublicKey))).checked
        assertEquals(built.fee.sat, 2 * ((checked.weight + 3) / 4).toLong())
    }

    @Test
    fun `an ancestor's quorum spends a descendant's coin through its leaf, on every device`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val statement = guardedStatement()
        tell(proposer, statement)
        tell(other, statement)

        val guarded = assertNotNull(GuardianManager.guardedWalletFor(proposer.db, roomId, guardedChildRoomId, chain, 0))
        val funding = fundingOf(guarded.account)
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.send(fundedState(guarded.account, funding, 100, 200), fr.acinq.bitcoin.Bitcoin.addressFromPublicKeyScript(chain.chainHash, elsewhere.toByteArray()).right!!,
                Satoshi(30_000), 2, emptySet(), emptySet(), guarded.spender())
        )

        val session = FrostSigningManager.proposeGuardianSpend(proposer.db, proposer.room, proposer.publicKey, built.psbt, guardedChildRoomId, chain)
        pump(proposer, other)
        assertEquals(FrostSigningSubject.TRANSACTION, other.session(session.id)?.subject)
        val seen = assertNotNull(FrostSigningManager.checkedSpend(other.db, session.id))
        assertIs<GroupSpend.Spender.Guardian>(seen.spender)
        assertEquals(listOf(false, true), seen.outputs.map { it.isChange }, "the change goes back to the child")

        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        listOf(proposer, other).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val row = assertNotNull(device.db.groupTransactionDao().getBySessionId(session.id))
            assertEquals(guardedChildRoomId, row.guardedChatRoomId)
            val signed = Transaction.read(assertNotNull(row.signedTransaction))
            signed.correctlySpends(mapOf(OutPoint(funding, 0) to funding.txOut[0]), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            assertEquals(3, signed.txIn[0].witness.stack.size, "a signature, the leaf, and its control block")
        }
    }

    @Test
    fun `the child reads off the chain which guardian took its coins, and says so once`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val statement = guardedStatement()
        tell(proposer, statement)
        tell(other, statement)

        val guarded = assertNotNull(GuardianManager.guardedWalletFor(proposer.db, roomId, guardedChildRoomId, chain, 0))
        val funding = fundingOf(guarded.account)
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.sendAll(fundedState(guarded.account, funding, 100, 200), fr.acinq.bitcoin.Bitcoin.addressFromPublicKeyScript(chain.chainHash, elsewhere.toByteArray()).right!!, 2, emptySet(), guarded.spender())
        )
        val session = FrostSigningManager.proposeGuardianSpend(proposer.db, proposer.room, proposer.publicKey, built.psbt, guardedChildRoomId, chain)
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)
        val signed = Transaction.read(assertNotNull(proposer.db.groupTransactionDao().getBySessionId(session.id)?.signedTransaction))

        // What the child's device knows: its own coin, and the transaction that spent it.
        val coins = mapOf(OutPoint(funding, 0) to (funding.txOut[0] to GroupWalletDerivation.derive(guarded.account, 0, 0)))
        val taken = GuardianManager.takenIn(signed, guarded.account, coins)
        assertEquals(listOf(GuardianManager.Taken(signed.txid, guarded.account.guardians[0], Satoshi(50_000))), taken)

        // The child's own key-path spend names nobody.
        val ownSpend = signed.updateWitness(0, fr.acinq.bitcoin.ScriptWitness(listOf(ByteVector(ByteArray(64)))))
        assertEquals(emptyList(), GuardianManager.takenIn(ownSpend, guarded.account, coins))

        // The line, written once, in the child's room -- the ancestor's name from
        // this device's own copy of its room.
        proposer.db.chatRoomDao().upsert(ChatRoom(id = guardedChildRoomId, userPublicKey = proposer.publicKey, subject = "Translation team", description = null, mlsGroupState = "00"))
        assertEquals(1, GuardianManager.recordTaken(proposer.db, guardedChildRoomId, taken))
        assertEquals(0, GuardianManager.recordTaken(proposer.db, guardedChildRoomId, taken), "a second reading of the chain writes nothing")
        val line = proposer.db.chatMessageDao().getChatMessagesByChatRoomId(guardedChildRoomId).map { it.chatMessage }
            .single { it.messageType == com.machankura.bennu.compose.database.model.ChatMessage.TYPE_GUARDIAN_SPEND }
        assertTrue(line.content.startsWith("Council took 50000 sats from this wallet through its guardian leaf"), line.content)
    }

    @Test
    fun `a guardian's spend is refused for a wallet the room was not told about, or without the lock`(): Unit = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        val statement = guardedStatement()
        tell(proposer, statement)
        // The other device was never told: it has no wallet to check the spend against.

        val guarded = assertNotNull(GuardianManager.guardedWalletFor(proposer.db, roomId, guardedChildRoomId, chain, 0))
        val funding = fundingOf(guarded.account)
        val built = assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.sendAll(fundedState(guarded.account, funding, 100, 200), fr.acinq.bitcoin.Bitcoin.addressFromPublicKeyScript(chain.chainHash, elsewhere.toByteArray()).right!!, 2, emptySet(), guarded.spender())
        )
        val session = FrostSigningManager.proposeGuardianSpend(proposer.db, proposer.room, proposer.publicKey, built.psbt, guardedChildRoomId, chain)
        pump(proposer, other)
        assertNull(other.session(session.id), "a device not told about the wallet has nothing to sign it against")

        // And the proposer's own device will not propose one that skips the lock.
        val unlocked = built.psbt.global.tx.let { tx -> tx.copy(txIn = tx.txIn.map { it.copy(sequence = GroupSpend.MAX_SEQUENCE) }) }
        val psbt = GroupSpend.psbtOf(unlocked, listOf(funding.txOut[0] to GroupWalletDerivation.derive(guarded.account, 0, 0)), emptyMap(), guarded.account)
        assertIs<GroupSpend.Result.Refused>(GroupSpend.check(psbt, guarded.account, guarded.spender(thresholdPublicKey)))
    }
}
