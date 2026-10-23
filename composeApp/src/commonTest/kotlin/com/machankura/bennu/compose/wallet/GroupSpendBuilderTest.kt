package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spends a member can build. Every one that is built is also put through
 * [GroupSpend.check], because a builder that produced spends the other members
 * refuse would be a builder that wastes a quorum every time.
 */
class GroupSpendBuilderTest {
    private val account = GroupWalletDerivation.account(
        PrivateKey(ByteVector32(Hex.decode("1c0ffee".padStart(64, '0')))).publicKey().toString(),
        Chain.Regtest,
    )
    private val destination = "bcrt1qw508d6qejxtdg4y5r3zarvary0c5xw7kygt080"
    private val feerate = 5L

    private fun coin(index: Int, sats: Long, height: Long = 100): Pair<String, WalletState.AddressState> {
        val derived = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index.toLong())
        val tx = Transaction(
            2,
            listOf(TxIn(OutPoint(TxId(ByteVector32(ByteArray(32) { index.toByte() })), 0), ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(sats), derived.pubkeyScript)),
            0,
        )
        val meta = WalletState.AddressMeta.Derived(index)
        return derived.address(Chain.Regtest) to WalletState.AddressState(
            meta, alreadyUsed = true, utxos = listOf(WalletState.Utxo(tx.txid, 0, height, tx, meta)),
        )
    }

    private fun state(vararg coins: Pair<String, WalletState.AddressState>): GroupWalletState {
        val unusedChange = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, 0)
        val change = WalletState(mapOf(unusedChange.address(Chain.Regtest) to WalletState.AddressState(WalletState.AddressMeta.Derived(0), false, emptyList())))
        return GroupWalletState(account, WalletState(coins.toMap()), change, tipHeight = 110)
    }

    private fun checked(result: GroupSpendBuilder.Result): GroupSpend.Checked {
        val built = assertIs<GroupSpendBuilder.Result.Built>(result)
        return assertIs<GroupSpend.Result.Ok>(GroupSpend.check(built.psbt, account)).checked
    }

    @Test
    fun `send all spends every confirmed coin to one output`() {
        val result = GroupSpendBuilder.sendAll(state(coin(0, 60_000), coin(1, 40_000)), destination, feerate, emptySet())
        val spend = checked(result)

        assertEquals(2, spend.inputs.size)
        assertEquals(1, spend.outputs.size)
        assertEquals(Satoshi(100_000), spend.amountSent + spend.fee)
        assertEquals(feerate, spend.feeratePerVbyte, "the fee is exact before signing")
    }

    @Test
    fun `an unconfirmed coin and a reserved one are left alone`() {
        val reservedCoin = coin(2, 30_000)
        val reserved = setOf(reservedCoin.second.utxos.single().outPoint)

        val spend = checked(
            GroupSpendBuilder.sendAll(state(coin(0, 60_000), coin(1, 40_000, height = 0), reservedCoin), destination, feerate, reserved)
        )

        assertEquals(1, spend.inputs.size)
        assertEquals(0L, spend.inputs.single().derivation.index)
    }

    @Test
    fun `an amount is paid largest coin first, with change the other members will recognise`() {
        val result = GroupSpendBuilder.send(state(coin(0, 20_000), coin(1, 60_000)), destination, Satoshi(30_000), feerate, emptySet())
        val built = assertIs<GroupSpendBuilder.Result.Built>(result)
        val spend = checked(result)

        assertEquals(listOf(1L), spend.inputs.map { it.derivation.index }, "the 60,000 coin alone covers it")
        val change = spend.outputs.single { it.isChange }
        assertEquals(GroupWalletDerivation.CHANGE, change.change!!.change)
        assertEquals(built.change, change.txOut.amount)
        assertEquals(Satoshi(30_000), spend.amountSent)
    }

    @Test
    fun `change that would be dust goes to fees instead`() {
        val spend = checked(GroupSpendBuilder.send(state(coin(0, 30_800)), destination, Satoshi(30_000), feerate, emptySet()))

        assertTrue(spend.outputs.none { it.isChange })
        assertEquals(Satoshi(800), spend.fee)
    }

    @Test
    fun `what cannot be built says why`() {
        val wallet = state(coin(0, 10_000))

        assertIs<GroupSpendBuilder.Result.NotEnough>(GroupSpendBuilder.send(wallet, destination, Satoshi(50_000), feerate, emptySet()))
        assertIs<GroupSpendBuilder.Result.NotAnAddress>(GroupSpendBuilder.sendAll(wallet, "not an address", feerate, emptySet()))
        assertIs<GroupSpendBuilder.Result.BelowDust>(GroupSpendBuilder.send(wallet, destination, Satoshi(100), feerate, emptySet()))
        assertIs<GroupSpendBuilder.Result.NothingToSpend>(GroupSpendBuilder.sendAll(state(), destination, feerate, emptySet()))
        // A mainnet address on a regtest wallet is not an address it can pay.
        assertNull((GroupSpendBuilder.sendAll(wallet, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", feerate, emptySet()) as? GroupSpendBuilder.Result.Built))
    }

    @Test
    fun `a bump pays more from the change, and the other members accept it`() {
        val first = checked(GroupSpendBuilder.send(state(coin(0, 60_000)), destination, Satoshi(30_000), feerate, emptySet()))

        val bumped = checked(GroupSpendBuilder.bump(first, feerate * 2))

        assertEquals(first.tx.txIn.map { it.outPoint }, bumped.tx.txIn.map { it.outPoint }, "the same coins")
        assertEquals(first.amountSent, bumped.amountSent, "the payment is untouched")
        assertEquals(feerate * 2, bumped.feeratePerVbyte)
        assertEquals(first.fee + first.outputs.single { it.isChange }.txOut.amount,
            bumped.fee + bumped.outputs.single { it.isChange }.txOut.amount, "the extra came out of the change")
    }

    @Test
    fun `a send-all bump comes out of the one payment, and a bump has to be higher`() {
        val first = checked(GroupSpendBuilder.sendAll(state(coin(0, 60_000)), destination, feerate, emptySet()))

        val bumped = checked(GroupSpendBuilder.bump(first, feerate * 3))
        assertEquals(Satoshi(60_000), bumped.amountSent + bumped.fee)

        assertIs<GroupSpendBuilder.Result.NotHigher>(GroupSpendBuilder.bump(first, feerate))
    }

    @Test
    fun `the mainnet cap refuses a spend over it, and test networks have none`() {
        val built = GroupSpendBuilder.sendAll(state(coin(0, 300_000)), destination, feerate, emptySet())

        assertIs<GroupSpendBuilder.Result.OverCap>(GroupSpendBuilder.capped(built, GroupWalletRollout.capFor(Chain.Mainnet)))
        assertIs<GroupSpendBuilder.Result.Built>(GroupSpendBuilder.capped(built, GroupWalletRollout.capFor(Chain.Regtest)))
        assertIs<GroupSpendBuilder.Result.Built>(
            GroupSpendBuilder.capped(GroupSpendBuilder.sendAll(state(coin(0, 50_000)), destination, feerate, emptySet()), GroupWalletRollout.capFor(Chain.Mainnet))
        )
    }
}
