package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.text.SatsInput
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.lightning.utils.sum

/**
 * The spends a member can propose from the group's wallet: everything to one
 * address, which is what Phoenix's final wallet does, or an amount with change.
 *
 * Confirmed coins only, as Phoenix's send-all takes, and never a coin an open
 * proposal is already spending. Two proposals over one coin are not dangerous --
 * only one can confirm, and each has its own nonces -- but the second is a quorum
 * asked for nothing.
 */
object GroupSpendBuilder {
    /** Phoenix's floor, kept: an output below it is one no node will relay. */
    val DUST: Satoshi = Satoshi(546)

    sealed interface Result {
        data class Built(val psbt: Psbt, val amount: Satoshi, val fee: Satoshi, val change: Satoshi?) : Result
        data object NotAnAddress : Result
        data object NothingToSpend : Result
        data object NotEnough : Result
        data object BelowDust : Result
        /** A bump has to pay more than the spend it replaces. */
        data object NotHigher : Result
        /** More than [GroupWalletRollout.capFor] lets one spend send. */
        data class OverCap(val cap: Satoshi) : Result
        /** The amount typed is not a number of sats; [input] says why. */
        data class InvalidAmount(val input: SatsInput) : Result
    }

    /**
     * [result] held to [cap]: the composer's gate while the wallet is new. A
     * built spend sending more than the cap is refused here, before a quorum is
     * asked; nothing at acceptance refuses one another build proposes.
     */
    fun capped(result: Result, cap: Satoshi?): Result =
        if (cap != null && result is Result.Built && result.amount > cap) Result.OverCap(cap) else result

    /** Every confirmed, unreserved coin, to [address], less the fee. */
    fun sendAll(state: GroupWalletState, address: String, feeratePerVbyte: Long, reserved: Set<OutPoint>): Result {
        val script = scriptOf(state, address) ?: return Result.NotAnAddress
        val coins = spendable(state, reserved).ifEmpty { return Result.NothingToSpend }

        val total = coins.map { it.amount }.sum()
        val fee = feeOf(coins, listOf(script), feeratePerVbyte)
        val amount = total - fee
        if (amount < DUST) return Result.BelowDust

        val tx = transaction(coins, listOf(TxOut(amount, script)))
        return Result.Built(psbtOf(state, tx, coins, change = emptyMap()), amount, fee, change = null)
    }

    /**
     * [amount] to [address], largest coins first, with what is left over paid
     * back to the next change address -- or to fees, when it would be dust.
     */
    fun send(
        state: GroupWalletState,
        address: String,
        amount: Satoshi,
        feeratePerVbyte: Long,
        reserved: Set<OutPoint>,
    ): Result {
        val script = scriptOf(state, address) ?: return Result.NotAnAddress
        if (amount < DUST) return Result.BelowDust
        val changeIndex = state.nextChangeIndex ?: return Result.NothingToSpend
        val change = GroupWalletDerivation.derive(state.account, GroupWalletDerivation.CHANGE, changeIndex)

        val coins = spendable(state, reserved).sortedByDescending { it.amount.sat }.ifEmpty { return Result.NothingToSpend }

        val chosen = mutableListOf<GroupUtxo>()
        for (coin in coins) {
            chosen += coin
            val total = chosen.map { it.amount }.sum()
            val withChange = feeOf(chosen, listOf(script, change.pubkeyScript), feeratePerVbyte)
            val leftover = total - amount - withChange

            if (leftover >= DUST) {
                val tx = transaction(chosen, listOf(TxOut(amount, script), TxOut(leftover, change.pubkeyScript)))
                return Result.Built(psbtOf(state, tx, chosen, mapOf(1 to change)), amount, withChange, leftover)
            }

            // Enough to pay without change: the remainder, under dust, goes to fees.
            val withoutChange = feeOf(chosen, listOf(script), feeratePerVbyte)
            if (total - amount >= withoutChange) {
                val tx = transaction(chosen, listOf(TxOut(amount, script)))
                return Result.Built(psbtOf(state, tx, chosen, emptyMap()), amount, total - amount, change = null)
            }
        }
        return Result.NotEnough
    }

    /**
     * [spend] again at [feeratePerVbyte]: the same coins and the same outputs,
     * with the extra fee taken from the change -- or, for a send-all, from the
     * one payment. RBF, which every spend allows; a new proposal, with new
     * signatures, because a retry is a new session.
     */
    fun bump(spend: GroupSpend.Checked, feeratePerVbyte: Long): Result {
        val fee = Satoshi(feeratePerVbyte * ((spend.weight + 3) / 4))
        val extra = fee - spend.fee
        if (extra.sat <= 0) return Result.NotHigher

        val target = spend.outputs.firstOrNull { it.isChange } ?: spend.payments.singleOrNull() ?: return Result.NotEnough
        val reduced = target.txOut.amount - extra
        if (reduced < DUST) return Result.NotEnough

        val tx = spend.tx.copy(txOut = spend.tx.txOut.mapIndexed { index, out -> if (index == target.index) out.copy(amount = reduced) else out })
        val psbt = GroupSpend.psbtOf(
            tx,
            spend.inputs.map { it.txOut to it.derivation },
            spend.outputs.mapNotNull { output -> output.change?.let { output.index to it } }.toMap(),
            spend.account,
        )
        return Result.Built(
            psbt = psbt,
            amount = if (target.isChange) spend.amountSent else reduced,
            fee = fee,
            change = if (target.isChange) reduced else null,
        )
    }

    private fun spendable(state: GroupWalletState, reserved: Set<OutPoint>): List<GroupUtxo> =
        state.utxos.filter { it.isConfirmed && it.utxo.outPoint !in reserved }

    private fun scriptOf(state: GroupWalletState, address: String): ByteVector? =
        Bitcoin.addressToPublicKeyScript(state.account.chain.chainHash, address.trim()).right
            ?.let { ByteVector(Script.write(it)) }

    private fun transaction(coins: List<GroupUtxo>, outputs: List<TxOut>) = Transaction(
        version = 2,
        // Replaceable, so a stuck spend can be bumped by a new proposal.
        txIn = coins.map { TxIn(it.utxo.outPoint, ByteVector.empty, GroupSpend.MAX_SEQUENCE) },
        txOut = outputs,
        lockTime = 0,
    )

    /** The fee at [feeratePerVbyte] for spending [coins] to [scripts]: exact, because every witness is 64 bytes. */
    private fun feeOf(coins: List<GroupUtxo>, scripts: List<ByteVector>, feeratePerVbyte: Long): Satoshi {
        val tx = transaction(coins, scripts.map { TxOut(Satoshi(0), it) })
        val weight = GroupSpend.witnessed(tx, List(coins.size) { ByteVector64.Zeroes }).weight()
        return Satoshi(feeratePerVbyte * ((weight + 3) / 4))
    }

    private fun psbtOf(state: GroupWalletState, tx: Transaction, coins: List<GroupUtxo>, change: Map<Int, GroupWalletDerivation.Derived>) =
        GroupSpend.psbtOf(tx, coins.map { it.utxo.txOut to it.derivation }, change, state.account)
}
