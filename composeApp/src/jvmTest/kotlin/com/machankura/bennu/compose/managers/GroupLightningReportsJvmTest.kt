package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.database.model.IcebergChannelLedger
import com.machankura.bennu.compose.database.model.MarmotGroupEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.network.serialization.CommonJson
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.wallet.lightning.CoSignerPolicy
import com.machankura.bennu.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.lightning.Lightning.randomBytes32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * What the operator's node tells the room, and what each member makes of it
 * (`docs/group-lightning-wallet.md`, Phase 10): a snapshot is believed only from the operator of the term
 * it names, every member files it with each channel checked against its own co-signing ledger, and the
 * round-one assignments it carries reach whoever operates next.
 */
class GroupLightningReportsJvmTest {
    private val fixture = GroupLightningFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()
    private val key = GroupLightningCoSigner.walletKey(fixture.roomId, fixture.chain, 0)
    private val nodes = mutableListOf<FakeGroupNode>()

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    private val channel = WalletWire.ChannelView("cc".repeat(32), "Normal", 1_000_000, 400_000_000, 600_000_000, "dd".repeat(32), 0, 3)
    private val payment = WalletWire.PaymentView("p1", incoming = true, amountMsat = 21_000_000, feesMsat = 0, paymentHash = "ee".repeat(32), description = "lunch", completedAt = 1_700_000_000_000, succeeded = true)

    /** Four devices with a Lightning wallet open, [operator] elected and running the group's node. */
    private suspend fun running(operator: Int = 1): Pair<List<GroupLightningFixture.Device>, GroupLightningNode> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        val who = all[operator]
        fixture.signSession(all, who, GroupLightningOperatorManager.nominateSelf(who.db, who.room, who.publicKey, fixture.chain).id)
        val id = randomBytes32().toHex()
        who.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = who.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        who.db.marmotGroupEventDao().upsert(MarmotGroupEvent(id = id, userPublicKey = who.publicKey, publicKey = who.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now()))
        val node = GroupLightningNode(
            who.db, who.room, who.publicKey, GroupLightningWalletManager.walletFor(who.db, fixture.roomId, fixture.chain)!!, scope,
            { configuration -> FakeGroupNode(configuration).also { nodes += it } },
            // The tests drive reporting themselves.
            handoverCheck = 10.minutes,
        )
        assertIs<GroupLightningNode.State.Running>(node.start())
        return all to node
    }

    private fun snapshotEvent(from: GroupLightningFixture.Device, snapshot: WalletWire.Snapshot) =
        Event(randomBytes32().toHex(), from.publicKey, Clock.System.now().epochSeconds, GroupLightningEvents.SNAPSHOT, emptyArray(), CommonJson.encodeToString(WalletWire.Snapshot.serializer(), snapshot), "")

    @Test
    fun `the operator's snapshot reaches every member, checked against what each one signed`() = runBlocking<Unit> {
        val (all, node) = running()
        nodes.single().channels = listOf(channel)
        nodes.single().payments += payment
        node.report(term = 1)
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))

        // One member signed the operator's figure, one signed another, one signed nothing for the channel.
        fun ledger(oursMsat: Long) = IcebergChannelLedger(key, channel.channelId, "${channel.fundingTxId}:0", lsp, GroupLightningCoSigner.store(CoSignerPolicy.Ledger(3, "tx", oursMsat, emptyMap(), emptyMap(), 0)))
        all[2].db.icebergCoSigningDao().upsertChannelLedger(ledger(400_000_000))
        all[3].db.icebergCoSigningDao().upsertChannelLedger(ledger(390_000_000))

        val reports = all.map { assertNotNull(GroupLightningReports.latest(it.db, key), "device ${it.index} filed the snapshot") }
        reports.forEach {
            assertEquals(1, it.snapshot.term)
            assertEquals(400_000_000, it.snapshot.spendableMsat)
            assertEquals(600_000_000, it.snapshot.inboundMsat)
        }
        assertEquals(listOf(null, null, true, false), reports.map { it.checks.single().agrees })
        all.forEach { d ->
            val filed = d.db.groupLightningRecordsDao().observePayments(key).first()
            assertEquals(listOf("lunch"), filed.map { it.description }, "device ${d.index} kept the payment")
        }
    }

    @Test
    fun `nothing changed is not sent again until the snapshot is due`() = runBlocking<Unit> {
        val (all, node) = running()
        val sent = { runBlocking { fixture.outbox(all[1], setOf(GroupLightningEvents.SNAPSHOT)).size } }
        assertEquals(1, sent(), "starting sends one")
        node.report(term = 1)
        assertEquals(1, sent())
        nodes.single().channels = listOf(channel)
        node.report(term = 1)
        assertEquals(2, sent())
    }

    @Test
    fun `only the operator of the term a snapshot names is believed`() = runBlocking<Unit> {
        val (all, _) = running()
        fixture.pump(all, setOf(GroupLightningEvents.SNAPSHOT))
        val wallet = GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
        val forged = WalletWire.Snapshot(key, 1, wallet.statement.nodeId, Clock.System.now().epochSeconds + 60, listOf(channel.copy(oursMsat = 999_000_000)), emptyList())
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[2], forged))
        assertEquals(0, GroupLightningReports.latest(all[3].db, key)!!.snapshot.spendableMsat, "the forged one did not replace the operator's")

        val otherNode = forged.copy(nodeId = "02" + "77".repeat(32))
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[1], otherNode))
        assertEquals(0, GroupLightningReports.latest(all[3].db, key)!!.snapshot.spendableMsat, "nor one about some other node")

        // The operator's own, carrying the labels it handed out, files them for whoever operates next.
        val handover = forged.copy(assignments = mapOf("verification:x:y:0" to 5L))
        GroupLightningReports.process(all[3].db, all[3].room, snapshotEvent(all[1], handover))
        assertEquals(999_000_000, GroupLightningReports.latest(all[3].db, key)!!.snapshot.spendableMsat)
        val assignment = assertNotNull(all[3].db.icebergCoSigningDao().assignmentFor(key, "verification:x:y:0"))
        assertEquals(5L, assignment.counter)
        assertEquals(GroupLightningCoSigner.poolLabel(wallet.statement.icebergKey, 5).toHex(), assignment.label)
        assertNull(all[2].db.icebergCoSigningDao().assignmentFor(key, "verification:x:y:0"))
    }
}
