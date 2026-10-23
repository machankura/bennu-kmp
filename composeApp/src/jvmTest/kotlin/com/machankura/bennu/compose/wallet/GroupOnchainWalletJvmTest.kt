package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group's wallet watching a chain that only exists in [FakeElectrum].
 */
class GroupOnchainWalletJvmTest {
    private fun key(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    private val account = GroupWalletDerivation.account(key(0x1c0ffee), Chain.Regtest)
    private fun receive(index: Long) = GroupWalletDerivation.derive(account, GroupWalletDerivation.RECEIVE, index)
    private fun change(index: Long) = GroupWalletDerivation.derive(account, GroupWalletDerivation.CHANGE, index)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `being paid moves the receive address on`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.receiveAddress == receive(0).address(Chain.Regtest) } }
        eventually { electrum.pay(receive(0).pubkeyScript, Satoshi(25_000)) }

        val paid = eventually { wallet.state.first { it.receiveAddress == receive(1).address(Chain.Regtest) } }
        assertEquals(Satoshi(25_000), paid.confirmedBalance)
        assertEquals(receive(0), paid.utxos.single().derivation)
    }

    @Test
    fun `change is the group's money and is not handed out to be paid`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.nextChangeIndex == 0L } }
        eventually { electrum.pay(change(0).pubkeyScript, Satoshi(7_000), height = 0) }

        val state = eventually { wallet.state.first { it.nextChangeIndex == 1L && it.receiveAddress != null } }
        assertEquals(Satoshi(7_000), state.pendingBalance, "in the mempool, so pending")
        assertEquals(Satoshi(0), state.confirmedBalance)
        assertEquals(receive(0).address(Chain.Regtest), state.receiveAddress, "a change address is never offered for receiving")
        assertEquals(change(0), state.derivationOf(change(0).pubkeyScript))
    }

    @Test
    fun `two groups on one client see only their own coins`() {
        val electrum = FakeElectrum()
        val other = GroupWalletDerivation.account(key(0xdecaf), Chain.Regtest)
        val ours = GroupOnchainWallet(account, electrum, scope)
        val theirs = GroupOnchainWallet(other, electrum, scope)

        eventually { ours.state.first { it.receiveAddress != null } }
        eventually { theirs.state.first { it.receiveAddress != null } }
        eventually { electrum.pay(GroupWalletDerivation.derive(other, 0, 0).pubkeyScript, Satoshi(50_000)) }

        eventually { theirs.state.first { it.confirmedBalance == Satoshi(50_000) } }
        val state = eventually { ours.state.first { it.receiveAddress != null } }
        assertTrue(state.utxos.isEmpty())
        assertNull(state.derivationOf(GroupWalletDerivation.derive(other, 0, 0).pubkeyScript), "another group's address is not ours to vouch for")
    }

    @Test
    fun `confirmations follow the tip`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.receiveAddress != null } }
        eventually { electrum.pay(receive(0).pubkeyScript, Satoshi(25_000), height = 100) }
        eventually { electrum.notifications.emit(HeaderSubscriptionResponse(105, Block.RegtestGenesisBlock.header)) }

        val state = eventually { wallet.state.first { it.tipHeight == 105 && it.utxos.isNotEmpty() } }
        assertEquals(6, state.confirmations(state.utxos.single()))
    }

    @Test
    fun `the receive branch is watched past the first unused address`() {
        val electrum = FakeElectrum()
        GroupOnchainWallet(account, electrum, scope).let { wallet ->
            eventually { wallet.state.first { it.receive.addresses.size == GroupOnchainWallet.RECEIVE_LOOKAHEAD.toInt() } }
        }
        assertTrue(FakeElectrum.scriptHash(receive(9).pubkeyScript) in electrum.subscribed)
    }

    private fun GroupWalletState.listed(): List<Pair<Long, GroupAddress.Status>>? =
        receiveAddresses?.map { it.derivation.index to it.status }

    @Test
    fun `a fresh wallet lists the current address and the two after it`() {
        val wallet = GroupOnchainWallet(account, FakeElectrum(), scope)

        val state = eventually { wallet.state.first { it.receiveAddresses != null } }
        assertEquals(
            listOf(2L to GroupAddress.Status.NEXT, 1L to GroupAddress.Status.NEXT, 0L to GroupAddress.Status.CURRENT),
            state.listed(),
        )
        assertEquals(state.receiveAddress, state.receiveAddresses!!.last().address, "the current row is the address the wallet screen shows")
    }

    @Test
    fun `a paid address stays in the list and the list moves on with it`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.receiveAddresses != null } }
        eventually { electrum.pay(receive(0).pubkeyScript, Satoshi(25_000)) }

        val state = eventually { wallet.state.first { it.receiveAddress == receive(1).address(Chain.Regtest) } }
        assertEquals(
            listOf(
                3L to GroupAddress.Status.NEXT,
                2L to GroupAddress.Status.NEXT,
                1L to GroupAddress.Status.CURRENT,
                0L to GroupAddress.Status.USED,
            ),
            state.listed(),
        )
        assertEquals(Satoshi(25_000), state.receiveAddresses!!.last().balance)
    }

    @Test
    fun `a paid address that has been spent from is still used`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.receiveAddresses != null } }
        eventually { electrum.pay(receive(0).pubkeyScript, Satoshi(25_000)) }
        eventually { wallet.state.first { it.receiveAddress == receive(1).address(Chain.Regtest) } }
        eventually { electrum.spend(receive(0).pubkeyScript) }

        val state = eventually { wallet.state.first { it.utxos.isEmpty() && it.receiveAddresses != null } }
        val spent = state.receiveAddresses!!.single { it.derivation.index == 0L }
        assertEquals(GroupAddress.Status.USED, spent.status)
        assertEquals(Satoshi(0), spent.balance)
        assertEquals(receive(1).address(Chain.Regtest), state.receiveAddress, "an emptied address is not handed out again")
    }

    @Test
    fun `a payment past the current address is listed however far up it is`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.receiveAddresses != null } }
        eventually { electrum.pay(receive(6).pubkeyScript, Satoshi(10_000)) }

        val state = eventually { wallet.state.first { it.receiveAddresses?.any { it.status == GroupAddress.Status.USED } == true } }
        assertEquals(
            listOf(6L to GroupAddress.Status.USED) +
                (5L downTo 1L).map { it to GroupAddress.Status.NEXT } +
                listOf(0L to GroupAddress.Status.CURRENT),
            state.listed(),
            "the lowest unused address is still the current one",
        )
    }

    @Test
    fun `every listed address is one the wallet watches`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.receiveAddresses != null } }
        eventually { electrum.pay(receive(0).pubkeyScript, Satoshi(1_000)) }
        eventually { electrum.pay(receive(1).pubkeyScript, Satoshi(1_000)) }

        val state = eventually { wallet.state.first { it.receiveAddress == receive(2).address(Chain.Regtest) } }
        val watched = state.receive.addresses.keys
        state.receiveAddresses!!.forEach { assertTrue(it.address in watched, "${it.derivation.index} is listed but not watched") }
    }

    @Test
    fun `change addresses are never listed for receiving`() {
        val electrum = FakeElectrum()
        val wallet = GroupOnchainWallet(account, electrum, scope)

        eventually { wallet.state.first { it.nextChangeIndex == 0L } }
        eventually { electrum.pay(change(0).pubkeyScript, Satoshi(7_000)) }

        val state = eventually { wallet.state.first { it.nextChangeIndex == 1L && it.receiveAddresses != null } }
        assertTrue(state.receiveAddresses!!.none { it.address == change(0).address(Chain.Regtest) })
        assertTrue(state.receiveAddresses!!.all { it.derivation.change == GroupWalletDerivation.RECEIVE })
    }
}
