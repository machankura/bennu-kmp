package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.wallet.lightning.WalletWire
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.payment.Bolt11Invoice

/**
 * A stand-in for the group's lightning-kmp node: no LSP to connect to, channels and payments as a test
 * sets them, and invoices signed with the node key the configuration carries, so they verify as the
 * group's node's.
 */
class FakeGroupNode(private val configuration: GroupLightningNode.NodeConfiguration) : GroupLightningNode.PeerHandle {
    var connected = false
    var disconnected = false
    var channels = listOf<WalletWire.ChannelView>()
    val payments = mutableListOf<WalletWire.PaymentView>()
    val invoices = mutableListOf<Bolt11Invoice>()
    /** Every payment asked of the node, as (invoice, amount). */
    val paid = mutableListOf<Pair<String, Long>>()
    var paymentsSucceed = true

    override suspend fun connect(): Boolean { connected = true; return true }
    override fun disconnect() { disconnected = true }
    override fun commitmentNumbers(): Map<String, Long> = mapOf("ab".repeat(32) to 7L)
    override fun channels(): List<WalletWire.ChannelView> = channels
    override fun payments(): List<WalletWire.PaymentView> = payments.toList()
    override suspend fun createInvoice(amountMsat: Long?, description: String): String =
        Bolt11Invoice.create(
            configuration.chain, amountMsat?.let(::MilliSatoshi), randomBytes32(), configuration.keyManager.nodeKeys.nodeKey.privateKey,
            Either.Left(description), CltvExpiryDelta(144), INVOICE_FEATURES,
        ).also { invoices += it }.write()
    override suspend fun hasPaid(paymentHash: String): Boolean = payments.any { !it.incoming && it.paymentHash == paymentHash }
    override suspend fun pay(bolt11: String, amountMsat: Long): WalletWire.PaymentView {
        paid += bolt11 to amountMsat
        val invoice = Bolt11Invoice.read(bolt11).get()
        return WalletWire.PaymentView("out${paid.size}", incoming = false, amountMsat = amountMsat, feesMsat = 1_000, paymentHash = invoice.paymentHash.toHex(), description = invoice.description, completedAt = 1, succeeded = paymentsSucceed)
            .also { payments += it }
    }
}

/** What a Bolt11 invoice must advertise to be read back at all. */
val INVOICE_FEATURES = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory)
