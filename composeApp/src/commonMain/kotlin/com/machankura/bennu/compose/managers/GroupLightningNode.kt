package com.machankura.bennu.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.bennu.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.bennu.compose.wallet.GroupChainClients
import com.machankura.bennu.compose.wallet.lightning.GroupNodeKeyManager
import com.machankura.bennu.compose.wallet.lightning.GroupSweep
import com.machankura.bennu.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.PaymentEvents
import fr.acinq.lightning.PeerConnected
import fr.acinq.lightning.WalletParams
import fr.acinq.lightning.blockchain.Feerates
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.GroupHeldOutputs
import fr.acinq.lightning.channel.states.ChannelStateWithCommitments
import fr.acinq.lightning.channel.states.Closed
import fr.acinq.lightning.channel.states.Closing
import fr.acinq.lightning.crypto.RemoteIcebergFundingSigner
import fr.acinq.lightning.db.Databases
import fr.acinq.lightning.db.LightningIncomingPayment
import fr.acinq.lightning.db.LightningOutgoingPayment
import fr.acinq.lightning.db.WalletPayment
import fr.acinq.lightning.io.PaymentSent
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.Init
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The group's Lightning node, on the operator's device (`docs/group-lightning-wallet.md`, Phase 8).
 *
 * A second lightning-kmp node beside the identity's own, built from the wallet's seed, with the group's
 * funding key, basepoints and closing address ([GroupNodeKeyManager]), and a remote Iceberg signer whose
 * transport ([GroupIcebergTransport]) reaches the members through the room. It runs only while this
 * device is the elected operator and has heard from the room recently ([GroupLightningOperatorManager.mayRunNode]),
 * and it stops itself the moment a newer term names somebody else -- telling the room where its
 * channels were as it goes (kind 30366).
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
    private val reportEvery: Duration = REPORT_EVERY,
    /** How many pool counters must hold round one before the node connects; 0 for a test with no co-signers. */
    private val minimumPool: Int = MINIMUM_POOL,
    private val poolWait: Duration = POOL_WAIT,
    /** How long to wait before redialling the LSP after [failures] attempts in a row that did not hold. */
    private val reconnectAfter: (failures: Int) -> Duration = ::reconnectDelay,
) {
    private val logger = Logger.withTag("GroupLightningNode")
    private val chain = wallet.statement.chain

    /** What a node needs to be built, gathered here so a [peerFactory] can build a real one or a fake. */
    class NodeConfiguration(
        val keyManager: GroupNodeKeyManager,
        val chain: Chain,
        val lspNodeId: PublicKey,
        val transport: GroupIcebergTransport,
        /** The most the group agreed to pay the LSP for the liquidity a payment needs: its statement's policy. */
        val maxLiquidityFee: Satoshi = Satoshi(GroupLightningWalletStatementEvent.Policy.DEFAULT.maxLiquidityFeeSat),
    )

    /** What this class needs of a running node. */
    interface PeerHandle {
        /** Dials the LSP; true once the handshake is done, false when it could not be. */
        suspend fun connect(): Boolean
        fun disconnect()
        /** Suspends until the connection to the LSP is down -- at once, when it is down already -- and says why. */
        suspend fun awaitDisconnect(): String
        /** Each channel's local commitment number, by channel id: what the stopped message reports. */
        fun commitmentNumbers(): Map<String, Long>
        /** The node's channels, as a snapshot reports them. */
        fun channels(): List<WalletWire.ChannelView>
        /** The payments the node has made or received while running, oldest first. */
        fun payments(): List<WalletWire.PaymentView>
        /** A Bolt11 invoice paying the node, for [amountMsat] or any amount. */
        suspend fun createInvoice(amountMsat: Long?, description: String): String
        /** Whether the node has tried to pay [paymentHash] before, in this run or an earlier one. */
        suspend fun hasPaid(paymentHash: String): Boolean
        /** Pays [bolt11] [amountMsat], through the LSP, and says how it went. */
        suspend fun pay(bolt11: String, amountMsat: Long): WalletWire.PaymentView
        /** Starts a mutual close of [channelId] to [scriptPubKey]. */
        suspend fun close(channelId: String, scriptPubKey: ByteVector)
        /** Publishes our own commitment of [channelId]. */
        suspend fun forceClose(channelId: String)
        /** The outputs closing channels pay to the group's basepoints, for the group to sweep. */
        fun heldOutputs(): List<GroupSweep.Input>
        /** The node's reusable Bolt12 offer, through the LSP: every member recomputes it from the seed. */
        fun offer(): String?
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
    private var connecting: Job? = null
    private var lastReported: Triple<List<WalletWire.ChannelView>, List<WalletWire.PaymentView>, List<GroupSweep.Input>>? = null
    private var lastReportedAt: kotlin.time.Instant? = null

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
        // Round one is served synchronously from the pool, so the pool has to be there before the LSP can
        // ask for anything: a channel that needs a label the pool lacks is aborted.
        if (!transport.awaitPool(minimumPool, poolWait)) {
            transport.stop()
            return State.Refused("the group has not sent enough round one to sign with yet").also { _state.value = it }
        }
        val onchain = GroupWalletManager.walletFor(database, roomId, chain, wallet.statement.onchainAccount)
            ?: return State.Refused("the onchain wallet it closes to is not open").also { _state.value = it }
        val keyManager = GroupNodeKeyManager(
            delegate = GroupLightningCoSigner.keyManagerOf(ByteVector(seed.seed), chain),
            fundingSigner = RemoteIcebergFundingSigner(PublicKey.fromHex(wallet.statement.icebergKey), transport, SIGNING_DEADLINE),
            basepoints = wallet.basepoints.forChannel,
            finalScriptPubKey = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript,
        )
        check(keyManager.nodeKeys.nodeKey.publicKey.toString() == wallet.statement.nodeId) { "the seed does not produce the statement's node" }

        val handle = peerFactory(NodeConfiguration(keyManager, chain, PublicKey.fromHex(wallet.statement.lspNodeId), transport, Satoshi(wallet.statement.policy.maxLiquidityFeeSat)))
        this.transport = transport
        peer = handle
        _state.value = State.Running(term)
        stayConnected(handle, runCatching { handle.connect() })
        lastReported = null
        report(term, force = true)
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
        // First, or the disconnect below would read as a drop and be redialled.
        connecting?.cancel()
        connecting = null
        peer?.disconnect()
        transport?.stop()
        peer = null
        transport = null
        _state.value = State.Stopped
        GroupLightningOperatorManager.announceStopped(database, localChatRoom, userPublicKey, chain, wallet.statement.wallet, running.term, numbers)
        logger.i("The group's node stopped at the end of term ${running.term}")
    }

    private val walletKey get() = GroupLightningCoSigner.walletKey(localChatRoom.chatRoom.id, chain, wallet.statement.wallet)

    /** Sends the room a snapshot when the channels or payments have changed, or when the last is [reportEvery] old. */
    internal suspend fun report(term: Long, force: Boolean = false) {
        val handle = peer ?: return
        val now = now()
        val current = Triple(handle.channels(), handle.payments(), handle.heldOutputs())
        val due = force || current != lastReported || lastReportedAt.let { it == null || now - it >= reportEvery }
        if (!due) return
        val snapshot = WalletWire.Snapshot(
            wallet = walletKey, term = term, nodeId = wallet.statement.nodeId, sentAt = now.epochSeconds,
            channels = current.first, payments = current.second.takeLast(REPORTED_PAYMENTS),
            assignments = transport?.assignments().orEmpty(),
            heldOutputs = current.third,
            offer = handle.offer(),
        )
        GroupLightningReports.publishSnapshot(database, localChatRoom, userPublicKey, snapshot)
        lastReported = current
        lastReportedAt = now
    }

    /** Makes an invoice for every request in the room that has none yet. */
    internal suspend fun answerInvoiceRequests() {
        val handle = peer ?: return
        GroupLightningReports.unanswered(database, localChatRoom.chatRoom.id, walletKey).forEach { request ->
            val bolt11 = handle.createInvoice(request.amountMsat, request.description)
            GroupLightningReports.answer(database, localChatRoom, userPublicKey, request, bolt11)
        }
    }

    private val paying = mutableSetOf<String>()

    /**
     * Pays every payment the group has authorised and this node has not tried yet, each once. A payment
     * that fails is not tried again: the group signs a new authorisation for another attempt, and the
     * co-signers' budgets are what stop either from paying twice.
     */
    internal suspend fun payAuthorised() {
        val handle = peer ?: return
        GroupLightningAuthorisations.toPay(database, wallet, now()).forEach { pay ->
            if (pay.paymentHash in paying || handle.hasPaid(pay.paymentHash)) return@forEach
            paying += pay.paymentHash
            scope.launch {
                runCatching { handle.pay(pay.invoice, pay.amountMsat) }
                    .onSuccess { logger.i("Paid ${pay.paymentHash.take(16)}: ${if (it.succeeded) "sent" else "not sent"}") }
                    .onFailure { logger.e("Paying ${pay.paymentHash.take(16)} failed", it) }
            }
        }
    }

    private val closing = mutableSetOf<String>()

    /**
     * Closes every channel the group has authorised closing, once per authorisation this run: mutually to
     * the onchain wallet's first address, which is where the channel's own closing script already
     * points, or by force.
     */
    internal suspend fun closeAuthorised() {
        val handle = peer ?: return
        val open = handle.channels().map { it.channelId }.toSet()
        val actions = GroupLightningAuthorisations.channelActions(database, localChatRoom.chatRoom.id, wallet.statement, now())
        if (actions.isEmpty()) return
        val onchain = GroupWalletManager.walletFor(database, localChatRoom.chatRoom.id, chain, wallet.statement.onchainAccount) ?: return
        val closeTo = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript
        actions.filter { it.channelId in open }.forEach { action ->
            if (!closing.add("${action.action}:${action.channelId}")) return@forEach
            when (action.action) {
                GroupLightningAuthorisationEvent.FORCE_CLOSE -> handle.forceClose(action.channelId)
                else -> handle.close(action.channelId, closeTo)
            }
            logger.i("${action.action} of ${action.channelId.take(16)}, as the group authorised")
        }
    }

    /**
     * Redials the LSP each time the connection ends, logging why it ended, until the node stops. lightning-kmp
     * does not reconnect by itself, and the identity's own node has Phoenix's connection daemon to do it; this
     * one had nothing, so a single drop left it offline for the rest of the term.
     *
     * A connection that ends inside [STABLE_CONNECTION] counts against the backoff like one that never came
     * up: an LSP that completes the handshake and hangs up on reading our features would otherwise be redialled
     * every few seconds for as long as the term lasts.
     */
    private fun stayConnected(handle: PeerHandle, first: Result<Boolean>) {
        connecting?.cancel()
        connecting = scope.launch {
            var attempt = first
            var since = now()
            var failures = 0
            while (isActive) {
                val reason = attempt.fold({ handle.awaitDisconnect() }, { it.message ?: it::class.simpleName.orEmpty() })
                val connected = attempt.getOrDefault(false)
                val lasted = now() - since
                failures = if (connected && lasted >= STABLE_CONNECTION) 1 else failures + 1
                val wait = reconnectAfter(failures)
                logger.w(
                    if (connected) "The group's node lost the LSP after $lasted: $reason. Reconnecting in $wait"
                    else "The group's node could not reach the LSP: $reason. Trying again in $wait"
                )
                delay(wait)
                since = now()
                attempt = runCatching { handle.connect() }
            }
        }
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
                runCatching { report(term) }.onFailure { logger.e("Could not report the wallet to the room", it) }
                runCatching { answerInvoiceRequests() }.onFailure { logger.e("Could not answer an invoice request", it) }
                runCatching { payAuthorised() }.onFailure { logger.e("Could not make an authorised payment", it) }
                runCatching { closeAuthorised() }.onFailure { logger.e("Could not close an authorised channel", it) }
            }
        }
    }

    companion object {
        /** A lightning-kmp payment as the room hears of it. */
        internal fun paymentView(payment: WalletPayment, incoming: Boolean) = WalletWire.PaymentView(
            id = payment.id.toString(),
            incoming = incoming,
            amountMsat = payment.amount.msat,
            feesMsat = payment.fees.msat,
            paymentHash = (payment as? LightningIncomingPayment)?.paymentHash?.toHex() ?: (payment as? LightningOutgoingPayment)?.paymentHash?.toHex(),
            description = ((payment as? LightningOutgoingPayment)?.details as? LightningOutgoingPayment.Details.Normal)?.paymentRequest?.description,
            completedAt = payment.completedAt,
            succeeded = payment.succeededAt != null,
        )

        /** How long a signature may take the group. Phase 0 was to measure what the LSP tolerates; until it is, a minute. */
        val SIGNING_DEADLINE: Duration = 60.seconds
        val HANDOVER_CHECK: Duration = 10.seconds
        /** Pool counters with round one in hand before the node connects: enough to open a channel and pay. */
        const val MINIMUM_POOL = 8
        /** How long a starting node waits for them before refusing; it is tried again at the next refresh. */
        val POOL_WAIT: Duration = 30.seconds

        val FEERATES_REFRESH: Duration = 10.minutes
        val FEERATES_RETRY: Duration = 30.seconds
        private val REGTEST_FEERATES = FeeratePerByte(Satoshi(2)).let { Feerates(it, it, it, it, it) }

        /** How stale a snapshot may grow while nothing changes. */
        val REPORT_EVERY: Duration = 5.minutes
        /** How many of the newest payments a snapshot carries; members keep every one they have seen. */
        const val REPORTED_PAYMENTS = 50

        /** How long a connection to the LSP has to last to reset the backoff. */
        val STABLE_CONNECTION: Duration = 1.minutes
        val RECONNECT_FIRST: Duration = 2.seconds
        val RECONNECT_MAX: Duration = 5.minutes
        /** How long a drop's reason waits for the LSP's init, which can reach the node after the socket closes. */
        val INIT_GRACE: Duration = 1.seconds

        /** [RECONNECT_FIRST], doubling with each attempt in a row that did not hold, up to [RECONNECT_MAX]. */
        fun reconnectDelay(failures: Int): Duration =
            if (failures >= 20) RECONNECT_MAX else (RECONNECT_FIRST * (1 shl (failures - 1).coerceAtLeast(0))).coerceAtMost(RECONNECT_MAX)

        /**
         * Why a connection to the LSP ended, from the socket's reason and the features each side sent. The
         * features come first when they explain it: an LSP that does not offer a feature we require hangs up
         * after the Init exchange, and the socket only says it was closed.
         */
        internal fun dropReason(ours: Features, theirs: Features?, closed: Connection.CLOSED): String {
            val socket = closed.reason?.message ?: "closed by this node"
            if (theirs == null) return "$socket, before the LSP sent its features"
            val missing = ours.activated.filter { (feature, support) -> support == FeatureSupport.Mandatory && !theirs.hasFeature(feature) }.keys.map { it.rfcName }
            val unknownToUs = theirs.unknown.filter { it.bitIndex % 2 == 0 }.map { "bit ${it.bitIndex}" }
            return when {
                missing.isNotEmpty() -> "$socket: the LSP does not offer ${missing.joinToString()}, which this node requires"
                unknownToUs.isNotEmpty() -> "$socket: the LSP requires ${unknownToUs.joinToString()}, which this node does not know"
                else -> socket
            }
        }

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
                // The group's own limit, not lightning-kmp's default of 2,000 sats: the statement it signed
                // names what a channel opened on the fly may cost.
                liquidityPolicy = MutableStateFlow(
                    LiquidityPolicy.Auto(
                        inboundLiquidityTarget = null,
                        maxAbsoluteFee = configuration.maxLiquidityFee,
                        maxRelativeFeeBasisPoints = 3_000,
                        skipAbsoluteFeeCheck = false,
                        maxAllowedFeeCredit = 0.msat,
                    )
                ),
            )
            val walletParams = walletParamsFor(configuration.chain)
            require(walletParams.trampolineNode.id == configuration.lspNodeId) { "the wallet's LSP is not the node it would connect to" }
            val client = clients.clientFor(configuration.chain)
            val watcher = ElectrumWatcher(client, scope, loggerFactory)
            val databases = databasesFor(nodeParams.nodeId, configuration.chain)
            val peer = Peer(nodeParams, walletParams, client, watcher, databases, TcpSocket.Builder(), scope)
            // The peer learns the chain's height only from header notifications, and a shared client that
            // subscribed long ago sends the next one at the next block: until then the node can answer no
            // invoice request and take no payment. So it is told the current height as it starts.
            scope.launch {
                runCatching { peer.currentTipFlow.value = client.startHeaderSubscription().blockHeight }
            }
            // lightning-kmp asks for feerates once, as Electrum connects, which suits a phone app that runs
            // for minutes. The operator's node runs for days, and without feerates it cannot close a channel
            // at all -- a force close waits for them, silently. So it asks again, often while it has none.
            // Regtest has nothing to estimate from; there it takes a flat rate.
            scope.launch {
                while (isActive) {
                    val feerates = runCatching { client.getFeerates() }.getOrNull()
                        ?: REGTEST_FEERATES.takeIf { configuration.chain == Chain.Regtest }
                    feerates?.let { peer.onChainFeeratesFlow.value = OnChainFeerates(it) }
                    delay(if (feerates == null) FEERATES_RETRY else FEERATES_REFRESH)
                }
            }
            val payments = mutableListOf<WalletWire.PaymentView>()
            // The LSP's features as it sent them on the current connection, for saying why one ended.
            val lspInit = MutableStateFlow<Init?>(null)
            scope.launch {
                nodeParams.nodeEvents.collect { event ->
                    when (event) {
                        is PeerConnected -> lspInit.value = event.theirInit
                        is PaymentEvents.PaymentReceived -> payments += paymentView(event.payment, incoming = true)
                        is PaymentEvents.PaymentSent -> payments += paymentView(event.payment, incoming = false)
                        else -> {}
                    }
                }
            }
            object : PeerHandle {
                override suspend fun connect(): Boolean {
                    lspInit.value = null
                    return peer.connect(connectTimeout = 10.seconds, handshakeTimeout = 10.seconds)
                }
                override fun disconnect() = peer.disconnect()
                override suspend fun awaitDisconnect(): String {
                    val closed = peer.connectionState.first { it is Connection.CLOSED } as Connection.CLOSED
                    // An LSP that hangs up on our features does so milliseconds after its own init, and the
                    // socket reads as closed before the peer's event for that init has reached us: wait for it.
                    val theirs = withTimeoutOrNull(INIT_GRACE) { lspInit.filterNotNull().first() }
                    return dropReason(nodeParams.features.initFeatures(), theirs?.features, closed)
                }
                override fun commitmentNumbers(): Map<String, Long> = peer.channelsFlow.value.mapNotNull { (id, state) ->
                    (state as? ChannelStateWithCommitments)?.let { id.toHex() to it.commitments.localCommitIndex }
                }.toMap()
                override fun channels(): List<WalletWire.ChannelView> = peer.channelsFlow.value.mapNotNull { (id, state) ->
                    (state as? ChannelStateWithCommitments)?.commitments?.latest?.let { latest ->
                        WalletWire.ChannelView(
                            channelId = id.toHex(),
                            state = state::class.simpleName ?: "",
                            capacitySat = latest.fundingAmount.sat,
                            oursMsat = latest.localCommit.spec.toLocal.msat,
                            theirsMsat = latest.localCommit.spec.toRemote.msat,
                            fundingTxId = latest.fundingTxId.toString(),
                            fundingIndex = latest.fundingInput.index,
                            commitIndex = latest.localCommit.index,
                        )
                    }
                }.sortedBy { it.channelId }
                override fun payments(): List<WalletWire.PaymentView> = payments.toList()
                override suspend fun createInvoice(amountMsat: Long?, description: String): String =
                    peer.createInvoice(Lightning.randomBytes32(), amountMsat?.let(::MilliSatoshi), Either.Left(description)).write()
                override suspend fun hasPaid(paymentHash: String): Boolean =
                    databases.payments.listLightningOutgoingPayments(ByteVector32(paymentHash)).isNotEmpty()
                override suspend fun close(channelId: String, scriptPubKey: ByteVector) {
                    peer.mutualClose(ByteVector32(channelId), scriptPubKey, peer.onChainFeeratesFlow.value?.mutualCloseFeerate ?: FeeratePerKw(FeeratePerByte(Satoshi(2))))
                }
                override fun heldOutputs(): List<GroupSweep.Input> = peer.channelsFlow.value.values.mapNotNull { state ->
                    when (state) {
                        is Closing -> state
                        is Closed -> state.state
                        else -> null
                    }
                }.flatMap { closing ->
                    val latest = closing.commitments.latest
                    val keys = configuration.keyManager.channelKeys(closing.commitments.channelParams.localParams.fundingKeyPath)
                    buildList {
                        closing.localCommitPublished?.let { published ->
                            GroupHeldOutputs.inLocalCommit(keys, latest, published.commitTx)?.let(::add)
                            // Our HTLC transactions, once they spend our commitment: their delayed outputs are the group's too.
                            published.irrevocablySpent.values.filter { tx -> tx.txIn.any { it.outPoint.txid == published.commitTx.txid } }
                                .forEach { htlcTx -> GroupHeldOutputs.inHtlcTx(keys, latest, htlcTx)?.let(::add) }
                        }
                        closing.remoteCommitPublished?.let { GroupHeldOutputs.inRemoteCommit(keys, latest, latest.remoteCommit.remotePerCommitmentPoint, it.commitTx)?.let(::add) }
                        closing.nextRemoteCommitPublished?.let { published ->
                            latest.nextRemoteCommit?.let { next -> GroupHeldOutputs.inRemoteCommit(keys, latest, next.remotePerCommitmentPoint, published.commitTx)?.let(::add) }
                        }
                    }
                }.map(GroupSweep::inputOf).distinctBy { it.txid to it.index }
                override fun offer(): String = nodeParams.defaultOffer(walletParams.trampolineNode.id).offer.encode()
                override suspend fun forceClose(channelId: String) {
                    peer.send(WrappedChannelCommand(ByteVector32(channelId), ChannelCommand.Close.ForceClose))
                }
                override suspend fun pay(bolt11: String, amountMsat: Long): WalletWire.PaymentView {
                    val invoice = Bolt11Invoice.read(bolt11).get()
                    return when (val result = peer.payInvoice(MilliSatoshi(amountMsat), invoice)) {
                        // A payment that went is reported through the node's events, like every other.
                        is PaymentSent -> paymentView(result.payment, incoming = false)
                        else -> WalletWire.PaymentView(
                            id = result.request.paymentId.toString(), incoming = false, amountMsat = amountMsat, feesMsat = 0,
                            paymentHash = invoice.paymentHash.toHex(), description = invoice.description,
                            completedAt = Clock.System.now().toEpochMilliseconds(), succeeded = false,
                        ).also { payments += it }
                    }
                }
            }
        }
    }
}
