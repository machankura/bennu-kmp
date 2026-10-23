package com.machankura.bennu.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.IcebergChannelLedger
import com.machankura.bennu.compose.database.model.IcebergEchoRow
import com.machankura.bennu.compose.database.model.IcebergLabelLedger
import com.machankura.bennu.compose.database.model.IcebergNonceRow
import com.machankura.bennu.compose.database.model.IcebergShareRow
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.network.serialization.CommonJson
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.wallet.lightning.CoSignerPolicy
import com.machankura.bennu.compose.wallet.lightning.CoSigningWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.iceberg.Iceberg
import fr.acinq.bitcoin.crypto.iceberg.NonceContribution
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.Transactions
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * The co-signer every Iceberg participant runs: it answers the operator's node for the group, without a
 * person in the loop, under the policy the group signed (`docs/group-lightning-wallet.md`, Phase 9).
 *
 * Four kinds reach it. A **nonce batch** is filed: round one is published ahead of time, for a pool of
 * labels nothing has been assigned to yet. A **signing request** is checked -- the sender is the
 * current operator, the label is one from the pool this device has never answered, the transaction is
 * the one its context rebuilds to, and what it does to the group's money is something the group agreed
 * to -- and, if it passes, *echoed*: the co-signer tells the room the hash it would sign under that
 * label, and will never sign anything else under it. An **echo** is counted; once `n - t + 1`
 * participants have echoed the same hash for a label, this device signs, and once a member sees two
 * hashes echoed for one label it signs neither. A **signature share** is filed for the operator.
 *
 * Why `n - t + 1`: any two sets of that many share `n - 2t + 2` members, which is at least `t` when
 * `n >= 3t - 2`, and so contains an honest member who echoed only one hash. So no two different
 * messages can ever both be signed under one label (decision D7).
 */
object GroupLightningCoSigner {
    private val logger = Logger.withTag("GroupLightningCoSigner")

    /** How many round-one contributions a co-signer keeps published ahead of the operator's latest need. */
    const val POOL_WINDOW: Long = 64

    /** The most a single provision request makes a co-signer publish. */
    const val MAX_BATCH: Long = 128

    /** A swap-in xpub for [LocalKeyManager], which wants one; a group node never swaps in, so any valid one does. */
    internal const val UNUSED_SWAP_IN_TPUB = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"

    fun walletKey(chatRoomId: String, chain: Chain, wallet: Long): String = "$chatRoomId:${GroupWalletStatementEvent.chainName(chain)}:$wallet"

    /** The label of pool counter [counter] for the group key [icebergKey]: every member computes the same. */
    fun poolLabel(icebergKey: HexKey, counter: Long): ByteVector32 =
        Crypto.taggedHash(ByteVector(icebergKey).toByteArray() + Pack.writeInt64BE(counter), "bennu/iceberg/pool/v1")

    /** The key manager a group node runs, and a co-signer rebuilds commitments with. */
    fun keyManagerOf(seed: ByteVector, chain: Chain): LocalKeyManager = LocalKeyManager(seed, chain, UNUSED_SWAP_IN_TPUB)

    /** What a co-signer stores of a channel's ledger, in JSON. */
    @Serializable
    private data class StoredLedger(
        val commitIndex: Long,
        val commitTxId: String,
        val oursMsat: Long,
        val offered: Map<String, List<String>>,
        val incoming: Map<String, Long>,
        val floorMsat: Long,
    )

    internal fun store(l: CoSignerPolicy.Ledger) = CommonJson.encodeToString(
        StoredLedger.serializer(),
        StoredLedger(l.commitIndex, l.commitTxId, l.oursMsat, l.offered.entries.associate { (id, h) -> id.toString() to listOf(h.paymentHash, h.amountMsat.toString()) }, l.incoming.entries.associate { (id, a) -> id.toString() to a }, l.floorMsat),
    )

    private fun load(json: String): CoSignerPolicy.Ledger = CommonJson.decodeFromString(StoredLedger.serializer(), json).let { s ->
        CoSignerPolicy.Ledger(s.commitIndex, s.commitTxId, s.oursMsat, s.offered.entries.associate { (id, v) -> id.toLong() to CoSignerPolicy.OfferedHtlc(v[0], v[1].toLong()) }, s.incoming.entries.associate { (id, a) -> id.toLong() to a }, s.floorMsat)
    }

    /**
     * What the last counterparty commitment this device signed for [channelId] left the group, HTLCs it
     * offered excluded, or null when this device has signed none for that channel.
     */
    suspend fun signedOursMsat(database: BennuDatabase, walletKey: String, channelId: String): Long? =
        database.icebergCoSigningDao().channelLedger(walletKey, channelId)?.let { load(it.ledger).oursMsat }

    /** The wallet a message for [walletKey] is about, if this device believes it. */
    internal suspend fun walletOf(database: BennuDatabase, chatRoomId: String, walletKey: String): GroupLightningWalletManager.Wallet? {
        val parts = walletKey.split(":")
        if (parts.size != 3 || parts[0] != chatRoomId) return null
        val chain = GroupWalletStatementEvent.chainNamed(parts[1]) ?: return null
        return GroupLightningWalletManager.walletFor(database, chatRoomId, chain, parts[2].toLongOrNull() ?: return null)
    }

    private fun indexOf(wallet: GroupLightningWalletManager.Wallet, participant: HexKey): Int? =
        wallet.statement.participants.indexOf(participant.lowercase()).takeIf { it >= 0 }?.plus(1)

    /** Publishes this device's round-one contributions for every pool counter up to [upTo] it has not published yet. */
    suspend fun provision(database: BennuDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, upTo: Long) {
        val share = wallet.ceremony?.let(IcebergRitualManager::shareOf) ?: return
        val key = walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val me = userPublicKey.lowercase()
        val dao = database.icebergCoSigningDao()
        val from = (dao.highestNonce(key, me) ?: -1L) + 1
        val to = minOf(upTo, from + MAX_BATCH - 1)
        if (to < from) return
        val contributions = (from..to).map { counter ->
            Iceberg.generateNonce(share, null, poolLabel(wallet.statement.icebergKey, counter)).toByteArray().toHex()
                .also { dao.insertNonce(IcebergNonceRow(key, counter, me, it)) }
        }
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.NONCE_BATCH, CommonJson.encodeToString(CoSigningWire.NonceBatch.serializer(), CoSigningWire.NonceBatch(key, from, contributions)))
    }

    /** Routes one inbound co-signing message. [nostrPrivateKey] is not needed to sign: the share is. */
    suspend fun process(database: BennuDatabase, localChatRoom: LocalChatRoom, innerEvent: Event, userPublicKey: HexKey) {
        try {
            when (innerEvent.kind) {
                GroupLightningEvents.NONCE_BATCH -> fileNonces(database, localChatRoom, innerEvent)
                GroupLightningEvents.SIGNING_REQUEST -> handleRequest(database, localChatRoom, innerEvent, userPublicKey)
                GroupLightningEvents.ECHO -> fileEcho(database, localChatRoom, innerEvent, userPublicKey)
                GroupLightningEvents.SIGNATURE_SHARE -> fileShare(database, localChatRoom, innerEvent)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.e("Could not process co-signing kind ${innerEvent.kind}", e)
        }
    }

    private suspend fun fileNonces(database: BennuDatabase, localChatRoom: LocalChatRoom, event: Event) {
        val batch = CommonJson.decodeFromString(CoSigningWire.NonceBatch.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, batch.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        if (batch.contributions.size > MAX_BATCH) return
        batch.contributions.forEachIndexed { i, c ->
            database.icebergCoSigningDao().insertNonce(IcebergNonceRow(batch.wallet, batch.from + i, event.pubKey.lowercase(), c))
        }
    }

    private suspend fun fileShare(database: BennuDatabase, localChatRoom: LocalChatRoom, event: Event) {
        val message = CommonJson.decodeFromString(CoSigningWire.SignatureShareMessage.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, message.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        database.icebergCoSigningDao().insertShare(IcebergShareRow(message.wallet, message.requestId, event.pubKey.lowercase(), message.share))
    }

    /** The provision request: a signing request whose kind is `PROVISION`, asking for contributions up to its counter. */
    const val PROVISION = "PROVISION"

    private suspend fun handleRequest(database: BennuDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val request = CommonJson.decodeFromString(CoSigningWire.SigningRequest.serializer(), event.content)
        val roomId = localChatRoom.chatRoom.id
        val wallet = walletOf(database, roomId, request.wallet) ?: return
        if (wallet.ceremony?.share == null || userPublicKey.lowercase() !in wallet.statement.participants) return

        // Check 1: the current operator, in the current term.
        val operator = GroupLightningOperatorManager.current(database, roomId, wallet.statement.chain, wallet.statement.wallet)
        if (operator?.publicKey?.lowercase() != event.pubKey.lowercase() || operator.term != request.term) {
            logger.w("Refusing request ${request.requestId}: ${event.pubKey.take(8)} is not the operator of term ${operator?.term}")
            return
        }
        if (request.kind == PROVISION) {
            provision(database, localChatRoom, userPublicKey, wallet, request.counter)
            return
        }

        val refusal = check(database, roomId, wallet, request)
        if (refusal != null) {
            logger.w("Refusing request ${request.requestId}: $refusal")
            return
        }
        val message = messageOf(request)

        // Echo, and commit to this hash under this label for good. A label already answered under any
        // other message is never answered again.
        val dao = database.icebergCoSigningDao()
        val existing = dao.ledgerFor(request.wallet, request.label)
        if (existing != null && existing.messageHash != message.toHex()) {
            logger.w("Refusing request ${request.requestId}: label ${request.label.take(16)} was already echoed for another message")
            return
        }
        commitLedger(database, roomId, wallet, request)
        if (existing == null) {
            dao.insertLedger(IcebergLabelLedger(request.wallet, request.label, request.requestId, message.toHex()))
            dao.insertEcho(IcebergEchoRow(request.wallet, request.label, userPublicKey.lowercase(), message.toHex()))
            GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.ECHO, CommonJson.encodeToString(CoSigningWire.Echo.serializer(), CoSigningWire.Echo(request.wallet, request.requestId, request.label, message.toHex())))
        }
        maybeSign(database, localChatRoom, wallet, request.wallet, request.label, userPublicKey)
    }

    private suspend fun fileEcho(database: BennuDatabase, localChatRoom: LocalChatRoom, event: Event, userPublicKey: HexKey) {
        val echo = CommonJson.decodeFromString(CoSigningWire.Echo.serializer(), event.content)
        val wallet = walletOf(database, localChatRoom.chatRoom.id, echo.wallet) ?: return
        if (indexOf(wallet, event.pubKey) == null) return
        database.icebergCoSigningDao().insertEcho(IcebergEchoRow(echo.wallet, echo.label, event.pubKey.lowercase(), echo.messageHash))
        maybeSign(database, localChatRoom, wallet, echo.wallet, echo.label, userPublicKey)
    }

    /** Signs under [label] once enough participants echoed this device's hash and nobody echoed another. */
    private suspend fun maybeSign(database: BennuDatabase, localChatRoom: LocalChatRoom, wallet: GroupLightningWalletManager.Wallet, key: String, label: String, userPublicKey: HexKey) {
        val dao = database.icebergCoSigningDao()
        val mine = dao.ledgerFor(key, label) ?: return
        if (mine.signedAt != null) return
        val echoes = dao.echoesFor(key, label)
        if (echoes.any { it.messageHash != mine.messageHash }) {
            logger.e("Label ${label.take(16)} was echoed for two different messages: the operator is equivocating, and nobody signs it")
            return
        }
        val n = wallet.statement.participants.size
        val t = wallet.statement.threshold
        if (echoes.map { it.participant }.toSet().size < n - t + 1) return

        val request = requestFor(database, localChatRoom.chatRoom.id, mine.requestId) ?: return
        val share = wallet.ceremony?.let(IcebergRitualManager::shareOf) ?: return
        val groupKey = PublicKey.fromHex(wallet.statement.icebergKey)
        val remoteKey = PublicKey.fromHex(request.remoteFundingPubKey)
        val sortedKeys = Scripts.sort(listOf(groupKey, remoteKey))
        val tweak = Scripts.Taproot.musig2Aggregate(groupKey, remoteKey).tweak(Crypto.TaprootTweak.KeyPathTweak)
        val cache = when (val c = KeyAggCache.create(sortedKeys).second.tweak(tweak, isXonly = true)) {
            is Either.Right -> c.value.first
            is Either.Left -> return
        }
        val signatureShare = Iceberg.partialSign(
            share, null, ByteVector32(label), request.contributions.map { NonceContribution(it) }, groupKey, cache,
            ByteVector32(mine.messageHash), AggregatedNonce(ByteVector(request.cosignerAggregatedNonce)),
        )
        when (signatureShare) {
            is Either.Left -> logger.e("Could not sign under ${label.take(16)}", signatureShare.value)
            is Either.Right -> {
                dao.upsertLedger(mine.copy(signedAt = Clock.System.now()))
                GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.SIGNATURE_SHARE, CommonJson.encodeToString(CoSigningWire.SignatureShareMessage.serializer(), CoSigningWire.SignatureShareMessage(key, mine.requestId, label, signatureShare.value.toByteArray().toHex())))
                dao.insertShare(IcebergShareRow(key, mine.requestId, userPublicKey.lowercase(), signatureShare.value.toByteArray().toHex()))
            }
        }
    }

    private suspend fun requestFor(database: BennuDatabase, chatRoomId: String, requestId: String): CoSigningWire.SigningRequest? =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.SIGNING_REQUEST))
            .mapNotNull { runCatching { CommonJson.decodeFromString(CoSigningWire.SigningRequest.serializer(), it.content) }.getOrNull() }
            .firstOrNull { it.requestId == requestId }

    /** The hash the request's transaction signs: recomputed, never read off the request. */
    fun messageOf(request: CoSigningWire.SigningRequest): ByteVector32 {
        val tx = CoSigningWire.tx(request.tx)
        val input = CoSigningWire.inputInfo(request.input)
        return tx.hashForSigningTaprootKeyPath(0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT)
    }

    /**
     * Every check before an echo, as the plan numbers them. Null when the request may be signed;
     * otherwise why not.
     */
    private suspend fun check(database: BennuDatabase, roomId: String, wallet: GroupLightningWalletManager.Wallet, request: CoSigningWire.SigningRequest): String? {
        val statement = wallet.statement
        val dao = database.icebergCoSigningDao()

        // Check 2: a pool label, for the counter it claims, and its contributions are exactly what the
        // participants they name published for it.
        if (poolLabel(statement.icebergKey, request.counter).toHex() != request.label) return "the label is not pool counter ${request.counter}"
        val published = dao.noncesFor(request.wallet, request.counter).associate { it.contribution to it.participant }
        if (request.contributions.size < 2 * statement.threshold - 1) return "round one has fewer than 2t-1 contributions"
        val from = request.contributions.map { published[it] ?: return "a contribution was not published by the participant it names" }
        if (from.toSet().size != from.size) return "a participant's contribution appears twice"
        request.contributions.zip(from).forEach { (c, participant) ->
            if (ByteVector(c)[0].toInt() != indexOf(wallet, participant)) return "a contribution carries another participant's index"
        }

        // Check 3: the funding output is the group key with the counterparty's.
        val tx = CoSigningWire.tx(request.tx)
        val input = CoSigningWire.inputInfo(request.input)
        if (tx.txIn.size != 1 || tx.txIn[0].outPoint != input.outPoint) return "the transaction does not spend exactly the funding output"
        val remoteKey = PublicKey.fromHex(request.remoteFundingPubKey)
        val groupKey = PublicKey.fromHex(statement.icebergKey)
        val expected = Transactions.makeFundingInputInfo(input.outPoint.txid, input.outPoint.index, input.txOut.amount, groupKey, remoteKey, Transactions.CommitmentFormat.SimpleTaprootChannels)
        if (expected.txOut != input.txOut) return "the funding output is not the group's key with ${request.remoteFundingPubKey.take(16)}"

        // Check 4 onwards: what the transaction is.
        val context = request.context ?: return "the operator did not say what this signs"
        val params = CoSigningWire.params(context.params)
        if (params.localParams.nodeId.toString() != statement.nodeId) return "the channel is not the wallet's node's"
        if (params.remoteParams.nodeId.toString() != statement.lspNodeId) return "the channel is not with the wallet's LSP"
        val onchain = GroupWalletManager.walletFor(database, roomId, statement.chain, statement.onchainAccount) ?: return "the onchain wallet is not open"
        val closeTo = GroupWalletDerivation.derive(onchain.account, GroupWalletDerivation.RECEIVE, 0).pubkeyScript
        if (params.localParams.defaultFinalScriptPubKey != closeTo) return "the channel does not close to the group's onchain wallet"

        // The channel is pinned to its funding output the first time this device signs for it.
        val pinned = dao.channelLedger(request.wallet, params.channelId.toHex())
        if (pinned != null && (pinned.fundingOutPoint != "${input.outPoint.txid}:${input.outPoint.index}" || pinned.remoteFundingPubKey != request.remoteFundingPubKey)) {
            return "the channel's funding output changed"
        }

        return when (context.type) {
            "remote" -> checkRemote(database, wallet, request, context, params, tx, pinned)
            "closing" -> checkClosing(statement, context, tx, closeTo)
            "local" -> checkLocal(database, wallet, request, context, params, tx, pinned)
            else -> "an unknown kind of transaction"
        }
    }

    /**
     * Our own commitment, which the group signs only to force-close: authorised, rebuilt from its context,
     * and no older than the counterparty commitment this device last signed for the channel, less one.
     * The two numbers move together, so a local commitment further behind than that is one this channel
     * has revoked, and publishing it would hand the counterparty everything.
     */
    private suspend fun checkLocal(
        database: BennuDatabase,
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        params: fr.acinq.lightning.channel.ChannelParams,
        tx: Transaction,
        pinned: IcebergChannelLedger?,
    ): String? {
        if (!GroupLightningAuthorisations.forceCloseAuthorised(database, wallet.signedEvent.chatRoomId, wallet.statement, params.channelId)) return "the group has not authorised a force close"
        val seed = wallet.seed ?: return "this device does not hold the node's seed, and cannot rebuild the commitment"
        val commitParams = CoSigningWire.commit(context.commit ?: return "no commitment parameters")
        val commitIndex = context.commitIndex ?: return "no commitment number"
        pinned?.let { if (commitIndex < load(it.ledger).commitIndex - 1) return "commitment $commitIndex is older than this channel's current one, and revoked" }
        val keys = keyManagerOf(ByteVector(seed.seed), wallet.statement.chain).channelKeys(params.localParams.fundingKeyPath)
            .withGroupBasepoints(wallet.basepoints.forChannel)
        val (rebuilt, _) = Commitments.makeLocalTxs(
            channelParams = params,
            commitParams = commitParams,
            commitKeys = keys.localCommitmentKeys(params, commitIndex),
            commitTxNumber = commitIndex,
            localFundingPubkey = PublicKey.fromHex(wallet.statement.icebergKey),
            remoteFundingPubKey = PublicKey.fromHex(request.remoteFundingPubKey),
            commitmentInput = CoSigningWire.inputInfo(request.input),
            commitmentFormat = Transactions.CommitmentFormat.SimpleTaprootChannels,
            spec = CoSigningWire.spec(context.spec, params.channelId),
        )
        return if (rebuilt.tx != tx) "the commitment its context rebuilds to is not the one it asks to sign" else null
    }

    private suspend fun checkRemote(
        database: BennuDatabase,
        wallet: GroupLightningWalletManager.Wallet,
        request: CoSigningWire.SigningRequest,
        context: CoSigningWire.Context,
        params: fr.acinq.lightning.channel.ChannelParams,
        tx: Transaction,
        pinned: IcebergChannelLedger?,
    ): String? {
        val seed = wallet.seed ?: return "this device does not hold the node's seed, and cannot rebuild the commitment"
        val commitParams = CoSigningWire.commit(context.commit ?: return "no commitment parameters")
        val commitIndex = context.commitIndex ?: return "no commitment number"
        val perCommitmentPoint = PublicKey.fromHex(context.remotePerCommitmentPoint ?: return "no per-commitment point")
        val spec = CoSigningWire.spec(context.spec, params.channelId)
        val keys = keyManagerOf(ByteVector(seed.seed), wallet.statement.chain).channelKeys(params.localParams.fundingKeyPath)
            .withGroupBasepoints(wallet.basepoints.forChannel)
        val (rebuilt, _) = Commitments.makeRemoteTxs(
            channelParams = params,
            commitParams = commitParams,
            commitKeys = keys.remoteCommitmentKeys(params, perCommitmentPoint),
            commitTxNumber = commitIndex,
            localFundingPubkey = PublicKey.fromHex(wallet.statement.icebergKey),
            remoteFundingPubKey = PublicKey.fromHex(request.remoteFundingPubKey),
            commitmentInput = CoSigningWire.inputInfo(request.input),
            commitmentFormat = Transactions.CommitmentFormat.SimpleTaprootChannels,
            spec = spec,
        )
        if (rebuilt.tx != tx) return "the commitment its context rebuilds to is not the one it asks to sign"

        // The first commitment a device signs for a channel is where its floor starts; every later one
        // answers to the ledger.
        if (pinned == null) return null
        val budgets = GroupLightningAuthorisations.budgets(database, wallet)
        val closed = GroupLightningAuthorisations.expired(database, wallet)
        return when (val verdict = CoSignerPolicy.checkRemoteCommitment(load(pinned.ledger), commitIndex, tx.txid.toString(), spec, budgets, closed, wallet.statement.policy.inFlightCapSat * 1000)) {
            is CoSignerPolicy.Verdict.Refuse -> verdict.reason
            is CoSignerPolicy.Verdict.Sign -> null
        }
    }

    private fun checkClosing(statement: com.machankura.bennu.compose.nostr.lightning.GroupLightningWalletStatementEvent.Statement, context: CoSigningWire.Context, tx: Transaction, closeTo: ByteVector): String? {
        val local = ByteVector(context.localScript ?: return "no closing script")
        val remote = ByteVector(context.remoteScript ?: return "no counterparty script")
        if (local != closeTo) return "the close does not pay the group's onchain wallet"
        if (tx.txOut.any { it.publicKeyScript != local && it.publicKeyScript != remote }) return "the close pays a script it did not name"
        val ours = context.spec.toLocalMsat / 1000
        val paid = tx.txOut.filter { it.publicKeyScript == local }.sumOf { it.amount.sat }
        val allowance = statement.policy.commitmentFeeAllowanceSat
        return if (ours > allowance && paid < ours - allowance) "the close pays the group $paid sats of its $ours" else null
    }

    /** Records what this device let the commitment do, at echo time: it is committed to it from here. */
    private suspend fun commitLedger(database: BennuDatabase, roomId: String, wallet: GroupLightningWalletManager.Wallet, request: CoSigningWire.SigningRequest) {
        val context = request.context ?: return
        if (context.type != "remote") return
        val params = CoSigningWire.params(context.params)
        val spec = CoSigningWire.spec(context.spec, params.channelId)
        val tx = CoSigningWire.tx(request.tx)
        val dao = database.icebergCoSigningDao()
        val channelId = params.channelId.toHex()
        val pinned = dao.channelLedger(request.wallet, channelId)
        val allowance = wallet.statement.policy.commitmentFeeAllowanceSat * 1000
        val next = when (pinned) {
            null -> CoSignerPolicy.open(context.commitIndex ?: return, tx.txid.toString(), spec, allowance)
            else -> when (val verdict = CoSignerPolicy.checkRemoteCommitment(load(pinned.ledger), context.commitIndex ?: return, tx.txid.toString(), spec, GroupLightningAuthorisations.budgets(database, wallet), GroupLightningAuthorisations.expired(database, wallet), wallet.statement.policy.inFlightCapSat * 1000)) {
                is CoSignerPolicy.Verdict.Refuse -> return
                is CoSignerPolicy.Verdict.Sign -> {
                    GroupLightningAuthorisations.consume(database, wallet, verdict.consumed)
                    verdict.ledger
                }
            }
        }
        val input = CoSigningWire.inputInfo(request.input)
        dao.upsertChannelLedger(IcebergChannelLedger(request.wallet, channelId, "${input.outPoint.txid}:${input.outPoint.index}", request.remoteFundingPubKey, store(next)))
        logger.i("Channel ${channelId.take(16)} in $roomId: commitment ${next.commitIndex} leaves the group ${next.valueMsat} msat against a floor of ${next.floorMsat}")
    }
}
