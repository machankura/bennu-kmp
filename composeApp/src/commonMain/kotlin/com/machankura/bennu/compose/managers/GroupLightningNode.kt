package com.machankura.bennu.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.wallet.GroupChainClients
import com.machankura.bennu.compose.wallet.lightning.GroupNodeKeyManager
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.WalletParams
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.crypto.RemoteIcebergFundingSigner
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The group's Lightning node, on the operator's device (`docs/group-lightning-wallet.md`, Phase 8).
 *
 * A second lightning-kmp node beside the identity's own, built from the wallet's seed, with the group's
 * funding key, basepoints and closing address ([GroupNodeKeyManager]), and a remote Iceberg signer whose
 * transport ([GroupIcebergTransport]) reaches the members through the room. It runs only while this
 * device is the elected operator and has heard from the room recently ([GroupLightningOperatorManager.mayRunNode]),
 * and it stops itself the moment a newer term names somebody else -- telling the room where its
 * channels were as it goes (kind 30349).
 *
 * The node itself is behind [PeerHandle], so the life cycle can be tested without an LSP to connect to.
 */
class GroupLightningNode(
    private val database: BennuDatabase,
    private val localChatRoom: LocalChatRoom,
    private val userPublicKey: HexKey,
    val wallet: GroupLightningWalletManager.Wallet,
    private val scope: CoroutineScope,
    private val peerFactory: suspend (NodeConfiguration) -> PeerHandle,
    private val now: () -> kotlin.time.Instant = { Clock.System.now() },
    private val handoverCheck: Duration = HANDOVER_CHECK,
) {
    private val logger = Logger.withTag("GroupLightningNode")
    private val chain = wallet.statement.chain

    /** What a node needs to be built, gathered here so a [peerFactory] can build a real one or a fake. */
    class NodeConfiguration(val keyManager: GroupNodeKeyManager, val chain: Chain, val lspNodeId: PublicKey, val transport: GroupIcebergTransport)

    /** What this class needs of a running node. */
    interface PeerHandle {
        suspend fun connect(): Boolean
        fun disconnect()
        /** Each channel's local commitment number, by channel id: what the stopped message reports. */
        fun commitmentNumbers(): Map<String, Long>
    }

    sealed class State {
        data object Stopped : State()
        data class Running(val term: Long) : State()
        data class Refused(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    private var peer: PeerHandle? = null
    private var transport: GroupIcebergTransport? = null
    private var watching: Job? = null

    /** Starts the node if this device may run it now; otherwise says why not. */
    suspend fun start(): State {
        if (_state.value is State.Running) return _state.value
        val roomId = localChatRoom.chatRoom.id
        val operator = GroupLightningOperatorManager.current(database, roomId, chain, wallet.statement.wallet)
        val lastHeard = database.marmotGroupEventDao().newestSavedAt(roomId)
        if (!GroupLightningOperatorManager.mayRunNode(database, roomId, chain, userPublicKey, lastHeard, now(), wallet.statement.wallet)) {
            return State.Refused(
                if (operator?.publicKey != userPublicKey) "this device is not the operator" else "this device has not heard from the room recently enough to be sure it still is"
            ).also { _state.value = it }
        }
        val seed = wallet.seed ?: return State.Refused("this device does not hold the wallet's seed").also { _state.value = it }
        val term = operator!!.term

        val transport = GroupIcebergTransport.create(database, localChatRoom, userPublicKey, wallet, term, scope).also { it.start() }
        transport.provision(GroupLightningCoSigner.POOL_WINDOW)
        val onchain = GroupWalletManager.walletFor(database, roomId, chain, wallet.statement.onchainAccount)
            ?: return State.Refused("the onchain wallet it closes to is not open").also { _state.value = it }
        val keyManager = GroupNodeKeyManager(
            delegate = GroupLightningCoSigner.keyManagerOf(ByteVector(seed.seed), chain),
            fundingSigner = RemoteIcebergFundingSigner(PublicKey.fromHex(wallet.statement.icebergKey), transport, SIGNING_DEADLINE),
            basepoints = wallet.basepoints.forChannel,
            finalScriptPubKey = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript,
        )
        check(keyManager.nodeKeys.nodeKey.publicKey.toString() == wallet.statement.nodeId) { "the seed does not produce the statement's node" }

        val handle = peerFactory(NodeConfiguration(keyManager, chain, PublicKey.fromHex(wallet.statement.lspNodeId), transport))
        this.transport = transport
        peer = handle
        _state.value = State.Running(term)
        handle.connect()
        watchForHandover(term)
        logger.i("The group's node ${wallet.statement.nodeId.take(16)} is running, term $term")
        return _state.value
    }

    /** Stops the node and tells the room where its channels were. Safe to call when it is not running. */
    suspend fun stop() {
        watching?.cancel()
        halt()
    }

    /** [stop], minus cancelling the handover watch: the watch calls this itself, and must finish saying so. */
    private suspend fun halt() {
        val running = _state.value as? State.Running ?: return
        val numbers = peer?.commitmentNumbers().orEmpty()
        peer?.disconnect()
        transport?.stop()
        peer = null
        transport = null
        _state.value = State.Stopped
        GroupLightningOperatorManager.announceStopped(database, localChatRoom, userPublicKey, chain, wallet.statement.wallet, running.term, numbers)
        logger.i("The group's node stopped at the end of term ${running.term}")
    }

    /** Stops the node as soon as the room's newest operator statement names a later term. */
    private fun watchForHandover(term: Long) {
        watching?.cancel()
        watching = scope.launch {
            while (isActive) {
                delay(handoverCheck)
                val current = GroupLightningOperatorManager.current(database, localChatRoom.chatRoom.id, chain, wallet.statement.wallet)
                if (current == null || current.term != term || current.publicKey != userPublicKey) {
                    logger.i("Term $term is over; stopping the group's node")
                    halt()
                    return@launch
                }
            }
        }
    }

    companion object {
        /** How long a signature may take the group. Phase 0 was to measure what the LSP tolerates; until it is, a minute. */
        val SIGNING_DEADLINE: Duration = 60.seconds
        val HANDOVER_CHECK: Duration = 10.seconds

        /**
         * Builds real lightning-kmp nodes: simple taproot channels only, peer storage on, zero-conf with the
         * LSP alone, on the chain's Electrum connection, persisted in the databases [databasesFor] opens for
         * the node's id.
         */
        fun lightningKmpPeers(
            clients: GroupChainClients,
            databasesFor: (nodeId: PublicKey, chain: Chain) -> Databases,
            walletParamsFor: (Chain) -> WalletParams,
            loggerFactory: LoggerFactory,
            scope: CoroutineScope,
        ): suspend (NodeConfiguration) -> PeerHandle = { configuration ->
            val base = NodeParams(configuration.chain, loggerFactory, configuration.keyManager)
            val nodeParams = base.copy(
                features = base.features.copy(activated = base.features.activated + (Feature.SimpleTaprootChannels to FeatureSupport.Mandatory)),
                zeroConfPeers = setOf(configuration.lspNodeId),
                usePeerStorage = true,
            )
            val walletParams = walletParamsFor(configuration.chain)
            require(walletParams.trampolineNode.id == configuration.lspNodeId) { "the wallet's LSP is not the node it would connect to" }
            val client = clients.clientFor(configuration.chain)
            val watcher = ElectrumWatcher(client, scope, loggerFactory)
            val peer = Peer(nodeParams, walletParams, client, watcher, databasesFor(nodeParams.nodeId, configuration.chain), TcpSocket.Builder(), scope)
            object : PeerHandle {
                override suspend fun connect(): Boolean = peer.connect(connectTimeout = 10.seconds, handshakeTimeout = 10.seconds)
                override fun disconnect() = peer.disconnect()
                override fun commitmentNumbers(): Map<String, Long> = peer.channelsFlow.value.mapNotNull { (id, state) ->
                    (state as? ChannelStateWithCommitments)?.let { id.toHex() to it.commitments.localCommitIndex }
                }.toMap()
            }
        }
    }
}
