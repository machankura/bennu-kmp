package com.machankura.bennu.compose.wallet.lightning

import kotlinx.serialization.Serializable

/**
 * What the operator tells the group about its Lightning wallet, and how a member asks to be paid
 * (`docs/group-lightning-wallet.md`, Phases 10 and 11), as JSON inside Marmot inner events.
 */
object WalletWire {
    /** One channel as the operator's node holds it. */
    @Serializable
    data class ChannelView(
        val channelId: String,
        val state: String,
        val capacitySat: Long,
        val oursMsat: Long,
        val theirsMsat: Long,
        val fundingTxId: String,
        val fundingIndex: Long,
        val commitIndex: Long,
    )

    /** One payment the node made or received. [id] is lightning-kmp's own. */
    @Serializable
    data class PaymentView(
        val id: String,
        val incoming: Boolean,
        val amountMsat: Long,
        val feesMsat: Long,
        val paymentHash: String? = null,
        val description: String? = null,
        val completedAt: Long? = null,
        val succeeded: Boolean,
    )

    /**
     * Kind 30345: the operator's claim about the wallet, sent when something changes and every few
     * minutes while the node runs. [assignments] is the round-one pool's assignments, so an operator
     * elected after this one can sign under the verification nonces this one published.
     */
    @Serializable
    data class Snapshot(
        val wallet: String,
        val term: Long,
        val nodeId: String,
        val sentAt: Long,
        val channels: List<ChannelView>,
        val payments: List<PaymentView>,
        val offer: String? = null,
        val assignments: Map<String, Long> = emptyMap(),
    ) {
        val spendableMsat: Long get() = channels.sumOf { it.oursMsat }
        val inboundMsat: Long get() = channels.sumOf { it.theirsMsat }
    }

    /** Kind 30347: a member asks the operator for an invoice. */
    @Serializable
    data class InvoiceRequest(val wallet: String, val requestId: String, val amountMsat: Long? = null, val description: String)

    /** Kind 30348: the operator's answer, addressed to the whole room by [requestId]. */
    @Serializable
    data class InvoiceReply(val wallet: String, val requestId: String, val bolt11: String)
}
