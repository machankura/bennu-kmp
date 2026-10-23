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
import com.machankura.bennu.compose.database.model.types.FrostSigningSubject
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.nostr.dkg.DkgRitualEvents
import com.machankura.bennu.compose.nostr.frost.FrostSigningEvents
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.wallet.GroupSpend
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.psbt.Psbt
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * A spend from the group's wallet between two devices, through the real
 * signing manager, with the wire between them held by hand -- the harness
 * `SignedGroupKeyStateTest` uses, cut down to a Marmot room.
 *
 * The wallet is opened the way a group opens one, by signing its statement in
 * the same harness, so the spend is checked against a statement that went
 * through the same session machinery rather than one planted in a table.
 *
 * The refusals are driven by handing a device a hand-built proposal, which is
 * the only way to be a dishonest member here. Each asserts the device made no
 * session at all: a proposal refused at acceptance has nothing to approve, so
 * nothing of the member's can ever be published for it.
 */
class GroupSpendSigningJvmTest {
    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")),
        nParticipants = participants,
        threshold = threshold,
    )
    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }
    private val chain = Chain.Regtest
    private val account = GroupWalletDerivation.account(thresholdPublicKey, chain)

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
        suspend fun transaction(id: String) = db.groupTransactionDao().getBySessionId(id)
    }

    private suspend fun device(publicKey: HexKey, signerIndex: Int): Device {
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())
        members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }
        val chatRoom = ChatRoom(
            id = roomId, userPublicKey = publicKey, subject = "Treasury",
            description = SharedKeyDerivation.describe("Treasury."), mlsGroupState = "00",
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

    /** The group opens its wallet the way it would: a statement, proposed and signed by a quorum. */
    private suspend fun openWallet(proposer: Device, other: Device) {
        val template = GroupWalletStatementEvent.template(account)
        val session = FrostSigningManager.proposeSigning(
            proposer.db, proposer.room, proposer.publicKey, template.kind, template.tags, template.content,
        )
        pump(proposer, other)
        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)
        listOf(proposer, other).forEach {
            assertNotNull(GroupWalletManager.walletFor(it.db, roomId, chain), "the statement opens the wallet on every device")
        }
    }

    private val first = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 0)
    private val second = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, 1)
    private val change = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)

    private val funding = Transaction(
        version = 2,
        txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 0x42 })), 0), ByteVector.empty, 0xffffffffL)),
        txOut = listOf(TxOut(Satoshi(60_000), first.pubkeyScript), TxOut(Satoshi(40_000), second.pubkeyScript)),
        lockTime = 0,
    )

    private val elsewhere = Script.write(Script.pay2wpkh(PrivateKey(ByteVector32(ByteArray(32) { 9 })).publicKey())).let(::ByteVector)

    private fun spendTx(fee: Long = 1_000, sequence: Long = 0xfffffffdL) = Transaction(
        version = 2,
        txIn = listOf(
            TxIn(OutPoint(funding, 0), ByteVector.empty, sequence),
            TxIn(OutPoint(funding, 1), ByteVector.empty, sequence),
        ),
        txOut = listOf(TxOut(Satoshi(70_000), elsewhere), TxOut(Satoshi(30_000 - fee), change.pubkeyScript)),
        lockTime = 0,
    )

    private fun psbt(tx: Transaction = spendTx(), changeAt: Map<Int, GroupWalletDerivation.Derived> = mapOf(1 to change)): Psbt =
        GroupSpend.psbtOf(tx, listOf(funding.txOut[0] to first, funding.txOut[1] to second), changeAt, account)

    /** A 30332 from member 0, built by hand, under [sessionId]. */
    private fun proposalOf(content: String, sessionId: String = Random.nextBytes(32).toHex(), walletTags: Array<Array<String>> = FrostSigningEvents.walletTags("regtest", 0)): Queued {
        val tags = FrostSigningEvents.assembleTags(sessionId = sessionId, dkgSessionId = ceremonyId) + walletTags
        val createdAt = 1_700_000_000L
        return Queued(
            id = EventHasher.hashId(members[0], createdAt, FrostSigningEvents.TRANSACTION_PROPOSAL, tags, content),
            publicKey = members[0], kind = FrostSigningEvents.TRANSACTION_PROPOSAL, tags = tags, content = content,
            createdAt = Instant.fromEpochSeconds(createdAt),
        )
    }

    private fun sessionIdOf(queued: Queued) = FrostSigningEvents.parseSessionId(queued.tags)!!

    // ---- The spend, end to end ------------------------------------------

    @Test
    fun `a quorum spends the group's coins, and both devices hold the transaction`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val session = FrostSigningManager.proposeSpend(proposer.db, proposer.room, proposer.publicKey, psbt(), chain)
        pump(proposer, other)

        assertEquals(FrostSigningSubject.TRANSACTION, other.session(session.id)?.subject)
        assertNull(other.transaction(session.id)?.signedTransaction, "nothing is signed before the member approves")

        FrostSigningManager.approve(other.db, other.room, session.id)
        pump(proposer, other)

        listOf(proposer, other).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val signed = Transaction.read(assertNotNull(device.transaction(session.id)?.signedTransaction))
            // The network's question, asked of each device's copy.
            signed.correctlySpends(
                funding.txOut.mapIndexed { i, out -> OutPoint(funding, i.toLong()) to out }.toMap(),
                ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS,
            )
            assertEquals(spendTx().txid, signed.txid, "the txid fixed at proposal is the one that was signed")
        }

        // Each input its own instance: every positive assertion above still
        // passes if two share a nonce, and what sharing costs is the share.
        val items = proposer.db.frostSigningSessionDao().getItems(session.id)
        assertEquals(listOf(0, 1), items.map { it.inputIndex })
        assertNotEquals(items[0].aggregatedNonce, items[1].aggregatedNonce)
        assertNotEquals(items[0].nonceRandom, items[1].nonceRandom)
    }

    // ---- What a member refuses ------------------------------------------

    @Test
    fun `a spend from a wallet the group never opened is refused`() = runBlocking {
        val other = device(members[1], signerIndex = 1)

        val proposal = proposalOf(GroupSpend.encode(psbt()))
        deliver(other, proposal)

        assertNull(other.session(sessionIdOf(proposal)))
    }

    @Test
    fun `each check refuses the proposal outright`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val stranger = GroupWalletDerivation.derive(GroupWalletDerivation.account(
            PrivateKey(ByteVector32(ByteArray(32) { 5 })).publicKey().toString(), chain), 0, 0)

        val refusals = mapOf(
            // 2. an input that is not the group's: the claim names /0/0, the coin is a stranger's.
            "a foreign input" to GroupSpend.psbtOf(spendTx(), listOf(TxOut(Satoshi(60_000), stranger.pubkeyScript) to first, funding.txOut[1] to second), mapOf(1 to change), account),
            // 3. a sighash that would let whoever holds the signatures redirect the money.
            "a sighash other than the default" to psbt().let { p ->
                p.copy(inputs = p.inputs.mapIndexed { i, input ->
                    if (i == 0) (input as fr.acinq.bitcoin.psbt.Input.WitnessInput.PartiallySignedWitnessInput).copy(sighashType = SigHash.SIGHASH_ALL) else input
                })
            },
            // 4. a spend that cannot be replaced.
            "a final sequence" to psbt(spendTx(sequence = 0xffffffffL)),
            // 5. an output called change that is not where its claim says.
            "a mislabelled change output" to psbt(changeAt = mapOf(0 to change)),
            // 6. outputs worth more than the coins: no fee, and no transaction.
            "a spend paying out more than it spends" to psbt(spendTx(fee = -1_000)),
        )

        refusals.forEach { (name, bad) ->
            val proposal = proposalOf(GroupSpend.encode(bad))
            deliver(other, proposal)
            assertNull(other.session(sessionIdOf(proposal)), "$name must be refused")
        }

        // And a wallet the proposal names but the room never opened.
        val otherAccount = proposalOf(GroupSpend.encode(psbt()), walletTags = FrostSigningEvents.walletTags("regtest", 1))
        deliver(other, otherAccount)
        assertNull(other.session(sessionIdOf(otherAccount)), "an account the group did not open")
    }

    @Test
    fun `a second proposal under the same id changes nothing`() = runBlocking {
        val proposer = device(members[0], signerIndex = 0)
        val other = device(members[1], signerIndex = 1)
        openWallet(proposer, other)

        val sessionId = Random.nextBytes(32).toHex()
        deliver(other, proposalOf(GroupSpend.encode(psbt()), sessionId = sessionId))
        val before = other.db.frostSigningSessionDao().getItems(sessionId).map { it.message }
        val psbtBefore = other.transaction(sessionId)?.psbt

        // Every item's seed is committed to its sighash. A different transaction
        // under the same id would have those seeds sign a second message.
        deliver(other, proposalOf(GroupSpend.encode(psbt(spendTx(fee = 2_000))), sessionId = sessionId).copy(id = Random.nextBytes(32).toHex()))

        assertEquals(before, other.db.frostSigningSessionDao().getItems(sessionId).map { it.message })
        assertEquals(psbtBefore, other.transaction(sessionId)?.psbt)
    }

    @Test
    fun `a feerate past the ceiling is refused, and one under it is not`() {
        // One coin large enough that the ceiling is reachable: about 110 vbytes
        // at 2,000 sats each is a fee of roughly 220,000 sats.
        val big = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { 7 })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(10_000_000), first.pubkeyScript)), 0)
        fun spending(fee: Long) = GroupSpend.psbtOf(
            Transaction(2, listOf(TxIn(OutPoint(big, 0), ByteVector.empty, 0xfffffffdL)), listOf(TxOut(Satoshi(10_000_000 - fee), elsewhere)), 0),
            listOf(big.txOut[0] to first), emptyMap(), account,
        )

        kotlin.test.assertIs<GroupSpend.Result.Refused>(GroupSpend.check(spending(fee = 1_000_000), account))
        kotlin.test.assertIs<GroupSpend.Result.Ok>(GroupSpend.check(spending(fee = 5_000), account))
    }
}
