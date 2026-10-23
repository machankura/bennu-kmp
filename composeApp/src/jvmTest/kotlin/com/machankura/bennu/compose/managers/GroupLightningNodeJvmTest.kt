package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.database.model.MarmotGroupEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.network.serialization.CommonJson
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.wallet.lightning.GroupLightningNodes
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Lightning.randomBytes32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The group's node on the operator's device: built with the group's keys, started only by the elected
 * operator who has heard from the room recently, and stopped -- with a word to the room -- the moment a
 * later term begins (`docs/group-lightning-wallet.md`, Phase 8). The node itself is a stand-in: there is
 * no LSP here to connect to.
 */
class GroupLightningNodeJvmTest {
    private val fixture = GroupLightningFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()

    @AfterTest
    fun close() {
        scope.cancel()
        fixture.close()
    }

    private var configurations = mutableListOf<GroupLightningNode.NodeConfiguration>()
    private val peers = mutableListOf<FakeGroupNode>()
    private val factory: suspend (GroupLightningNode.NodeConfiguration) -> GroupLightningNode.PeerHandle = { configuration ->
        configurations += configuration
        FakeGroupNode(configuration).also { peers += it }
    }

    private suspend fun ready(): List<GroupLightningFixture.Device> {
        val all = fixture.group()
        fixture.runCeremony(all)
        val session = GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp)
        fixture.signSession(all, all[0], session.id)
        return all
    }

    private suspend fun elect(all: List<GroupLightningFixture.Device>, who: GroupLightningFixture.Device) {
        val session = GroupLightningOperatorManager.nominateSelf(who.db, who.room, who.publicKey, fixture.chain)
        fixture.signSession(all, who, session.id)
    }

    /** As if this device had just heard from the room. */
    private suspend fun heardFromRoom(device: GroupLightningFixture.Device) {
        // A kind:445 is a nostr event first, and its row hangs off that one.
        val id = randomBytes32().toHex()
        device.db.nostrEventDao().upsert(NostrEvent(id = id, pubKey = device.publicKey, kind = 445, tags = emptyArray(), content = "", sig = "0".repeat(128)))
        device.db.marmotGroupEventDao().upsert(
            MarmotGroupEvent(id = id, userPublicKey = device.publicKey, publicKey = device.publicKey, chatRoomId = fixture.roomId, signature = "", encryptedContent = "", expiresAt = null, savedAt = Clock.System.now())
        )
    }

    private suspend fun node(device: GroupLightningFixture.Device) = GroupLightningNode(
        device.db, device.room, device.publicKey, GroupLightningWalletManager.walletFor(device.db, fixture.roomId, fixture.chain)!!, scope, factory,
        handoverCheck = 100.milliseconds,
    )

    @Test
    fun `the node runs on the group's keys`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[1])
        val node = node(all[1])
        assertIs<GroupLightningNode.State.Running>(node.start())
        val keys = configurations.single().keyManager
        val wallet = node.wallet
        assertEquals(wallet.statement.nodeId, keys.nodeKeys.nodeKey.publicKey.toString())
        val channelKeys = keys.channelKeys(keys.newFundingKeyPath(isChannelOpener = false))
        assertEquals(PublicKey.fromHex(wallet.statement.icebergKey), channelKeys.fundingPublicKey(0))
        assertEquals(wallet.basepoints.forChannel.payment, channelKeys.paymentBasePoint)
        assertEquals(wallet.basepoints.forChannel.delayedPayment, channelKeys.delayedPaymentBasePoint)
        assertEquals(GroupWalletDerivation.derive(fixture.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript, keys.defaultFinalScriptPubKey)
        assertTrue(peers.single().connected)
        // Starting asked the group for round one ahead of any need.
        assertTrue(fixture.outbox(all[1], setOf(GroupLightningEvents.SIGNING_REQUEST)).isNotEmpty())
        assertTrue(fixture.outbox(all[1], setOf(GroupLightningEvents.NONCE_BATCH)).isNotEmpty())
        node.stop()
    }

    @Test
    fun `only the elected operator, who has heard from the room, may run it`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[2])
        assertIs<GroupLightningNode.State.Refused>(node(all[2]).start(), "a member who was not elected")
        assertIs<GroupLightningNode.State.Refused>(node(all[1]).start(), "the operator, who has heard nothing from the room")
        assertTrue(peers.isEmpty())
    }

    @Test
    fun `a later term stops the node, and the room is told where its channels were`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[1])
        heardFromRoom(all[1])
        val node = node(all[1])
        assertIs<GroupLightningNode.State.Running>(node.start())
        elect(all, all[2])
        withTimeout(5.seconds) { while (node.state.value is GroupLightningNode.State.Running) delay(50) }
        assertTrue(peers.single().disconnected)
        val stopped = fixture.outbox(all[1], setOf(GroupLightningEvents.STOPPED)).single()
        val body = CommonJson.decodeFromString(GroupLightningOperatorManager.Stopped.serializer(), stopped.content)
        assertEquals(1, body.term)
        assertEquals(mapOf("ab".repeat(32) to 7L), body.commitmentNumbers)
    }

    @Test
    fun `the identity's nodes start where it operates and nowhere else`() = runBlocking<Unit> {
        val all = ready()
        elect(all, all[3])
        all.forEach { heardFromRoom(it) }
        val operatorNodes = GroupLightningNodes(all[3].db, all[3].publicKey, scope, factory, fixture.chain)
        val otherNodes = GroupLightningNodes(all[0].db, all[0].publicKey, scope, factory, fixture.chain)
        operatorNodes.refresh()
        otherNodes.refresh()
        assertIs<GroupLightningNode.State.Running>(operatorNodes.nodeFor(fixture.roomId)?.state?.value)
        assertEquals(null, otherNodes.nodeFor(fixture.roomId))
        operatorNodes.stop()
        assertTrue(peers.single().disconnected)
        assertTrue(1.minutes > 0.seconds)
    }
}
