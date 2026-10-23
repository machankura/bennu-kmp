package com.machankura.bennu.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.GroupLightningInvoiceRow
import com.machankura.bennu.compose.database.model.GroupLightningPaymentRow
import com.machankura.bennu.compose.database.model.GroupLightningSnapshotRow
import com.machankura.bennu.compose.database.model.IcebergLabelAssignment
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.extensions.toHex
import com.machankura.bennu.compose.network.serialization.CommonJson
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.wallet.lightning.WalletWire
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.PublicKey
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferManager
import kotlin.time.Instant

/**
 * What the operator tells the room about the group's Lightning wallet, and how a member asks it for an
 * invoice (`docs/group-lightning-wallet.md`, Phases 10 and 11).
 *
 * Everything here is the operator's claim. A member keeps it to show, checks what it can -- that the
 * sender is the operator of the term it names, that an invoice pays the node the group signed for, that
 * a channel's balance is what this device last co-signed -- and decides nothing with it: the co-signer
 * reads its own ledgers, never a snapshot.
 */
object GroupLightningReports {
    private val logger = Logger.withTag("GroupLightningReports")

    /** One channel as the snapshot reports it, beside what this device last co-signed for it. */
    data class ChannelCheck(val channel: WalletWire.ChannelView, val signedOursMsat: Long?) {
        /** Whether the operator's figure is the one this device signed; null when it signed nothing for the channel. */
        val agrees: Boolean? get() = signedOursMsat?.let { it == channel.oursMsat }
    }

    /** Sends [snapshot] to the room, and files it here as every member would. */
    suspend fun publishSnapshot(database: BennuDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, snapshot: WalletWire.Snapshot) {
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.SNAPSHOT, CommonJson.encodeToString(WalletWire.Snapshot.serializer(), snapshot))
        file(database, userPublicKey, snapshot)
    }

    /** Routes one inbound snapshot or invoice. Invoice requests are read by the running node, not here. */
    suspend fun process(database: BennuDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        runCatching {
            when (innerEvent.kind) {
                GroupLightningEvents.SNAPSHOT -> receiveSnapshot(database, localChatRoom, innerEvent)
                GroupLightningEvents.INVOICE -> receiveInvoice(database, localChatRoom, innerEvent)
            }
        }.onFailure { logger.w("Could not read wallet report ${innerEvent.id}: ${it.message}") }
    }

    private suspend fun receiveSnapshot(database: BennuDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        val snapshot = CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), innerEvent.content)
        val wallet = GroupLightningCoSigner.walletOf(database, localChatRoom.chatRoom.id, snapshot.wallet) ?: return
        if (snapshot.nodeId != wallet.statement.nodeId) return logger.w("Snapshot ${innerEvent.id} is for a node the group did not sign for")
        // A snapshot from an earlier term's operator is still the truth about that term; one from
        // anybody else is nobody's.
        val operator = GroupLightningOperatorManager.ofTerm(database, localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet, snapshot.term)
        if (operator?.publicKey != innerEvent.pubKey) return logger.w("Snapshot ${innerEvent.id} is not from the operator of term ${snapshot.term}")
        // An offer is kept only if it is the one the group's node derives: the seed says which.
        file(database, innerEvent.pubKey, snapshot.copy(offer = snapshot.offer?.takeIf { it == offerOf(wallet) }))
        // The round-one labels the operator handed out, so whoever operates next signs under them.
        val dao = database.icebergCoSigningDao()
        snapshot.assignments.forEach { (purpose, counter) ->
            if (dao.assignmentFor(snapshot.wallet, purpose) == null) {
                val label = GroupLightningCoSigner.poolLabel(wallet.statement.icebergKey, counter).toHex()
                runCatching { dao.insertAssignment(IcebergLabelAssignment(snapshot.wallet, counter, label, purpose)) }
            }
        }
    }

    /**
     * The Bolt12 offer the group's node publishes, recomputed from the node seed the way lightning-kmp
     * derives it ([OfferManager.deterministicOffer]); null on a device that does not hold the seed.
     */
    fun offerOf(wallet: GroupLightningWalletManager.Wallet): String? {
        val seed = wallet.seed ?: return null
        val nodeKey = GroupLightningCoSigner.keyManagerOf(ByteVector(seed.seed), wallet.statement.chain).nodeKeys.nodeKey.privateKey
        return OfferManager.deterministicOffer(wallet.statement.chain.chainHash, nodeKey, PublicKey.fromHex(wallet.statement.lspNodeId), null, null, null).offer.encode()
    }

    private suspend fun file(database: BennuDatabase, sentBy: HexKey, snapshot: WalletWire.Snapshot) {
        val dao = database.groupLightningRecordsDao()
        val held = dao.snapshot(snapshot.wallet)
        val newer = held == null || snapshot.term > held.term || (snapshot.term == held.term && snapshot.sentAt >= held.sentAt.epochSeconds)
        if (newer) {
            dao.upsertSnapshot(GroupLightningSnapshotRow(snapshot.wallet, sentBy, snapshot.term, CommonJson.encodeToString(WalletWire.Snapshot.serializer(), snapshot), Instant.fromEpochSeconds(snapshot.sentAt)))
        }
        // Payments are kept whatever the snapshot's age: a list of what happened only grows.
        snapshot.payments.forEach { p ->
            dao.upsertPayment(GroupLightningPaymentRow(snapshot.wallet, p.id, p.incoming, p.amountMsat, p.feesMsat, p.paymentHash, p.description, p.completedAt?.let(Instant::fromEpochMilliseconds), p.succeeded))
        }
    }

    /** A snapshot, with each of its channels checked against this device's own ledger. */
    data class Report(val snapshot: WalletWire.Snapshot, val checks: List<ChannelCheck>)

    /** The newest snapshot this device holds for [walletKey], with each channel checked against its own ledger. */
    suspend fun latest(database: BennuDatabase, walletKey: String): Report? {
        val row = database.groupLightningRecordsDao().snapshot(walletKey) ?: return null
        val snapshot = CommonJson.decodeFromString(WalletWire.Snapshot.serializer(), row.content)
        return Report(snapshot, snapshot.channels.map { ChannelCheck(it, GroupLightningCoSigner.signedOursMsat(database, walletKey, it.channelId)) })
    }

    /** Asks the operator for an invoice; the answer arrives under the returned request id. */
    suspend fun requestInvoice(
        database: BennuDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        wallet: GroupLightningWalletManager.Wallet,
        amountMsat: Long?,
        description: String,
    ): String {
        val key = GroupLightningCoSigner.walletKey(localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        val requestId = RandomInstance.bytes(16).toHex()
        val request = WalletWire.InvoiceRequest(key, requestId, amountMsat, description)
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.INVOICE_REQUEST, CommonJson.encodeToString(WalletWire.InvoiceRequest.serializer(), request))
        return requestId
    }

    /** The invoice requests for [walletKey] in the room that no invoice answers yet, oldest first. */
    suspend fun unanswered(database: BennuDatabase, chatRoomId: String, walletKey: String): List<WalletWire.InvoiceRequest> =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GroupLightningEvents.INVOICE_REQUEST))
            .sortedBy { it.createdAt }
            .mapNotNull { runCatching { CommonJson.decodeFromString(WalletWire.InvoiceRequest.serializer(), it.content) }.getOrNull() }
            .filter { it.wallet == walletKey && database.groupLightningRecordsDao().invoice(walletKey, it.requestId) == null }

    /** Sends the room the operator's invoice for [request], and files it here. */
    suspend fun answer(database: BennuDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, request: WalletWire.InvoiceRequest, bolt11: String) {
        val reply = WalletWire.InvoiceReply(request.wallet, request.requestId, bolt11)
        GroupLightningWalletManager.queue(database, localChatRoom, userPublicKey, GroupLightningEvents.INVOICE, CommonJson.encodeToString(WalletWire.InvoiceReply.serializer(), reply))
        database.groupLightningRecordsDao().upsertInvoice(GroupLightningInvoiceRow(request.wallet, request.requestId, bolt11))
    }

    private suspend fun receiveInvoice(database: BennuDatabase, localChatRoom: LocalChatRoom, innerEvent: Event) {
        val reply = CommonJson.decodeFromString(WalletWire.InvoiceReply.serializer(), innerEvent.content)
        val wallet = GroupLightningCoSigner.walletOf(database, localChatRoom.chatRoom.id, reply.wallet) ?: return
        val operator = GroupLightningOperatorManager.current(database, localChatRoom.chatRoom.id, wallet.statement.chain, wallet.statement.wallet)
        if (operator?.publicKey != innerEvent.pubKey) return logger.w("Invoice ${innerEvent.id} is not from the operator")
        // Whoever sent it, an invoice that pays some other node is not the group's.
        val invoice = Bolt11Invoice.read(reply.bolt11).get()
        if (invoice.nodeId.toString() != wallet.statement.nodeId) return logger.w("Invoice ${innerEvent.id} pays a node that is not the group's")
        database.groupLightningRecordsDao().upsertInvoice(GroupLightningInvoiceRow(reply.wallet, reply.requestId, reply.bolt11))
    }
}
