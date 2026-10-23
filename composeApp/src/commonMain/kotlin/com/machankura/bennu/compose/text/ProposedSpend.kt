package com.machankura.bennu.compose.text

import com.machankura.bennu.compose.wallet.GroupSpend
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain

/**
 * A spend the group is being asked to sign, said in words -- [ProposedEvent]'s
 * counterpart for a transaction, and for the same reason not a composable: the
 * signing screen and the room's list of proposals say it the same way.
 */
object ProposedSpend {
    fun summarize(spend: GroupSpend.Checked, chain: Chain): ProposedEvent.Summary =
        ProposedEvent.Summary(
            // A guardian's spend takes a descendant's coins, so whose they are
            // is the first thing a signer reads.
            label = when (val guardian = spend.spender as? GroupSpend.Spender.Guardian) {
                null -> "Spend from the group's wallet"
                else -> "Spend from ${guardian.name ?: "a subgroup"}'s guarded wallet"
            },
            detail = when (val payment = spend.payments.singleOrNull()) {
                null -> "Send ${spend.amountSent.grouped()} sats to ${spend.payments.size} addresses"
                else -> "Send ${payment.txOut.amount.grouped()} sats to ${addressOf(payment.txOut.publicKeyScript, chain)}"
            }
        )

    /** The address [script] pays, or the script itself where it has no address. */
    fun addressOf(script: ByteVector, chain: Chain): String =
        Bitcoin.addressFromPublicKeyScript(chain.chainHash, script.toByteArray()).right ?: script.toHex()

    /** What a summary says for a spend this device cannot check: nothing a member could sign on. */
    val UNREADABLE = ProposedEvent.Summary(
        label = "Spend from the group's wallet",
        detail = "A spend this device cannot check"
    )
}
