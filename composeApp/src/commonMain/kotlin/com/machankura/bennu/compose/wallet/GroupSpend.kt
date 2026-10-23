package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.FrostSigningManager
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.SigHash
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Input
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.psbt.TaprootBip32DerivationPath
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.utils.sum
import kotlin.io.encoding.Base64

/**
 * A spend from a group's wallet, checked the way every member checks it before
 * putting their share behind it.
 *
 * Nothing here trusts the proposer. A PSBT says which coins it spends, where
 * under the wallet's account each one is, and which outputs are change; every
 * one of those claims is rederived from the account and refused if it does not
 * reproduce the script it is about. The sighashes are then computed here, from
 * the PSBT itself -- the counterpart of rebuilding a proposed event's id from
 * its fields rather than taking the id on the wire.
 *
 * ### Why a PSBT's amounts can be believed without fetching anything
 *
 * Before taproot a signer had to see each spent output's whole transaction to
 * trust its amount, or a proposer could lie it down and turn the difference into
 * fee. BIP341's sighash commits to every spent amount and every spent script, so
 * a lie here produces signatures the network rejects -- not a larger fee. That
 * is what lets a member with no node of their own, and so no way to look a coin
 * up, still sign safely: the worst a lying proposer gets is a transaction nobody
 * can mine.
 */
object GroupSpend {
    /**
     * The most inputs one spend signs: a transaction is a batch, one item per
     * input, so it is the batch cap.
     */
    const val MAX_INPUTS: Int = FrostSigningManager.MAX_BATCH_SIZE

    /**
     * How far down a branch a claimed derivation may point.
     *
     * A bound on work rather than on the wallet: each claim costs one derivation
     * to check, and a proposer must not be able to choose how much work everybody
     * else does. A member with no node has no watched range to bound it by, so it
     * is a constant, and far past any gap a group will leave.
     */
    const val MAX_ADDRESS_INDEX: Long = 100_000L

    /**
     * The highest feerate a spend may pay, in sats per virtual byte.
     *
     * Not a security check -- the approval shows the fee -- but a feerate typed
     * with three zeros too many is the likeliest way for a group to lose money,
     * and refusing it costs a retry.
     */
    const val MAX_FEERATE_SAT_PER_VBYTE: Long = 2_000L

    /** One input, and what its signature has to be over and verify against. */
    data class SpentInput(
        val index: Int,
        val outPoint: OutPoint,
        val txOut: TxOut,
        val derivation: GroupWalletDerivation.Derived,
        val sighash: ByteVector32,
    )

    /** One output, and -- when its claim to be the wallet's own derives -- where under the account it is. */
    data class CheckedOutput(
        val index: Int,
        val txOut: TxOut,
        val change: GroupWalletDerivation.Derived?,
    ) {
        val isChange: Boolean get() = change != null
    }

    data class Checked(
        val psbt: Psbt,
        val inputs: List<SpentInput>,
        val outputs: List<CheckedOutput>,
        /** `Σ inputs − Σ outputs`, believable for the reason the object's kdoc gives. */
        val fee: Satoshi,
        /** The finished transaction's weight. Exact before signing: a default-sighash key-path witness is always 64 bytes. */
        val weight: Int,
    ) {
        val tx: Transaction get() = psbt.global.tx

        val payments: List<CheckedOutput> get() = outputs.filterNot { it.isChange }

        /** What leaves the group: every output that is not its own change. */
        val amountSent: Satoshi get() = payments.map { it.txOut.amount }.sum()

        /** Sats per virtual byte, rounded down. */
        val feeratePerVbyte: Long get() = fee.sat / vbytes(weight)

        /** Every spent output, in input order: what every sighash and `correctlySpends` take. */
        val spentOutputs: List<TxOut> get() = inputs.map { it.txOut }
    }

    sealed interface Result {
        data class Ok(val checked: Checked) : Result
        data class Refused(val reason: String) : Result
    }

    /**
     * The checks a member makes before signing, in the order `docs/group-chain-wallet.md`
     * numbers them, against [account] -- which comes from the room's verified
     * statement and never from the proposal.
     */
    fun check(psbt: Psbt, account: GroupWalletDerivation.Account): Result {
        val tx = psbt.global.tx

        // 4. Bounded, before any per-input work.
        if (tx.txIn.isEmpty()) return Result.Refused("spends nothing")
        if (tx.txIn.size > MAX_INPUTS) return Result.Refused("spends ${tx.txIn.size} coins, more than the $MAX_INPUTS one spend may sign")
        if (tx.txOut.isEmpty()) return Result.Refused("pays nothing")
        if (tx.version != 2L) return Result.Refused("is version ${tx.version}, not 2")
        if (tx.lockTime != 0L) return Result.Refused("has a lock time")
        if (tx.txIn.any { it.sequence > MAX_SEQUENCE }) return Result.Refused("cannot be replaced: a sequence is above ${MAX_SEQUENCE}")
        if (tx.txIn.map { it.outPoint }.toSet().size != tx.txIn.size) return Result.Refused("spends one coin twice")

        // 2 and 3. Every input is the group's, at a key path, under the default sighash.
        val claimed = psbt.inputs.mapIndexed { index, input ->
            val witness = input as? Input.WitnessInput.PartiallySignedWitnessInput
                ?: return Result.Refused("input $index is not an unsigned segwit input")
            if (input.sighashType != null && input.sighashType != SigHash.SIGHASH_DEFAULT) {
                return Result.Refused("input $index asks for sighash ${input.sighashType}; only the default is signed")
            }
            // Script-path fields -- a leaf script, a merkle root, a script
            // signature -- are ones this library does not parse, so they land
            // here. Any of them means the coin is not the plain key-path output
            // this wallet's addresses are.
            if (input.unknown.isNotEmpty()) return Result.Refused("input $index carries fields this wallet does not sign with")
            if (input.taprootKeySignature != null) return Result.Refused("input $index is already signed")

            val derivation = claimedDerivation(input.taprootDerivationPaths, account)
                ?: return Result.Refused("input $index does not name a place under this wallet's account")
            if (derivation.pubkeyScript != witness.txOut.publicKeyScript) {
                return Result.Refused("input $index claims ${derivation.change}/${derivation.index}, which is not the coin it spends")
            }
            if (input.taprootInternalKey != null && input.taprootInternalKey != derivation.internalKey) {
                return Result.Refused("input $index names an internal key its derivation does not reach")
            }
            Triple(index, witness.txOut, derivation)
        }

        val spentOutputs = claimed.map { it.second }
        val inputs = claimed.map { (index, txOut, derivation) ->
            SpentInput(
                index = index,
                outPoint = tx.txIn[index].outPoint,
                txOut = txOut,
                derivation = derivation,
                sighash = tx.hashForSigningTaprootKeyPath(index, spentOutputs, SigHash.SIGHASH_DEFAULT),
            )
        }

        // 5. Change is change only if it derives.
        val outputs = psbt.outputs.mapIndexed { index, output ->
            val txOut = tx.txOut[index]
            val ours = output.taprootDerivationPaths.values.filter { it.masterKeyFingerprint == account.fingerprint }
            val change = if (ours.isEmpty()) {
                null
            } else {
                val derivation = claimedDerivation(output.taprootDerivationPaths, account)
                    ?: return Result.Refused("output $index claims a place under this wallet's account that is not one")
                if (derivation.pubkeyScript != txOut.publicKeyScript) {
                    return Result.Refused("output $index claims ${derivation.change}/${derivation.index}, which is not where it pays")
                }
                derivation
            }
            CheckedOutput(index = index, txOut = txOut, change = change)
        }

        // 6. A sane fee.
        val fee = spentOutputs.map { it.amount }.sum() - tx.txOut.map { it.amount }.sum()
        if (fee.sat <= 0) return Result.Refused("pays out more than it spends")
        val weight = witnessed(tx, List(tx.txIn.size) { ByteVector64.Zeroes }).weight()
        if (fee.sat > MAX_FEERATE_SAT_PER_VBYTE * vbytes(weight)) {
            return Result.Refused("pays a fee of ${fee.sat} sats, over $MAX_FEERATE_SAT_PER_VBYTE sats per byte")
        }

        return Result.Ok(Checked(psbt = psbt, inputs = inputs, outputs = outputs, fee = fee, weight = weight))
    }

    /**
     * The one derivation under [account] a PSBT field names, or null when it names
     * none, several, or one this wallet will not walk.
     *
     * Keyed by internal key in the PSBT, per BIP371; the key is checked against
     * the derivation rather than trusted, since it is the proposer's word.
     */
    private fun claimedDerivation(
        paths: Map<fr.acinq.bitcoin.XonlyPublicKey, TaprootBip32DerivationPath>,
        account: GroupWalletDerivation.Account,
    ): GroupWalletDerivation.Derived? {
        val (internalKey, path) = paths.entries.singleOrNull { it.value.masterKeyFingerprint == account.fingerprint }
            ?.toPair()
            ?: return null
        if (path.leaves.isNotEmpty()) return null
        val (change, index) = path.keyPath.path.takeIf { it.size == 2 } ?: return null
        if (change != GroupWalletDerivation.RECEIVE && change != GroupWalletDerivation.CHANGE) return null
        if (index !in 0L..MAX_ADDRESS_INDEX) return null

        return GroupWalletDerivation.derive(account, change, index).takeIf { it.internalKey == internalKey }
    }

    /**
     * The PSBT a proposer sends: [tx] with every input's spent output and
     * derivation, and every change output's derivation, so that a signer is
     * *told* what is change -- and [check] is how they decide whether to believe it.
     */
    fun psbtOf(
        tx: Transaction,
        spent: List<Pair<TxOut, GroupWalletDerivation.Derived>>,
        change: Map<Int, GroupWalletDerivation.Derived>,
        account: GroupWalletDerivation.Account,
    ): Psbt {
        require(spent.size == tx.txIn.size) { "one spent output per input" }

        val withInputs = spent.foldIndexed(Psbt(tx)) { index, psbt, (txOut, derivation) ->
            psbt.updateWitnessInput(
                outPoint = tx.txIn[index].outPoint,
                txOut = txOut,
                taprootInternalKey = derivation.internalKey,
                taprootDerivationPaths = mapOf(derivation.internalKey to pathOf(derivation, account)),
            ).right ?: throw IllegalArgumentException("could not describe input $index")
        }

        return change.entries.fold(withInputs) { psbt, (index, derivation) ->
            psbt.updateWitnessOutput(
                outputIndex = index,
                taprootInternalKey = derivation.internalKey,
                taprootDerivationPaths = mapOf(derivation.internalKey to pathOf(derivation, account)),
            ).right ?: throw IllegalArgumentException("could not describe output $index")
        }
    }

    private fun pathOf(derivation: GroupWalletDerivation.Derived, account: GroupWalletDerivation.Account) =
        TaprootBip32DerivationPath(
            leaves = emptyList(),
            masterKeyFingerprint = account.fingerprint,
            keyPath = KeyPath(listOf(derivation.change, derivation.index)),
        )

    /** [tx] with a key-path witness on every input, in input order. */
    fun witnessed(tx: Transaction, signatures: List<ByteVector64>): Transaction =
        signatures.foldIndexed(tx) { index, partial, signature ->
            partial.updateWitness(index, Script.witnessKeyPathPay2tr(signature))
        }

    fun encode(psbt: Psbt): String = Base64.Default.encode(Psbt.write(psbt).toByteArray())

    fun decode(content: String): Psbt? =
        runCatching { Base64.Default.decode(content.trim()) }.getOrNull()
            ?.let { Psbt.read(it).right }

    /** Every sequence at or below this signals BIP125 replaceability, so a stuck spend can be bumped by a new proposal. */
    const val MAX_SEQUENCE: Long = 0xfffffffdL

    private fun vbytes(weight: Int): Long = (weight.toLong() + 3) / 4
}
