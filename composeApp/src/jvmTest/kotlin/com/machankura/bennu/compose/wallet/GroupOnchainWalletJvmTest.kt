package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ElectrumSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.GetMerkleResponse
import fr.acinq.lightning.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.ScriptHashSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.TransactionHistoryItem
import fr.acinq.lightning.blockchain.electrum.UnspentItem
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group's wallet watching a chain that only exists in this file.
 *
 * `IElectrumClient` is small and documented never to throw, so a fake of it is
 * a few maps: what each script has been paid, and the transactions that paid it.
 */
class GroupOnchainWalletJvmTest {
    private class FakeElectrum : IElectrumClient {
        val unspents = mutableMapOf<ByteVector32, List<UnspentItem>>()
        val txs = mutableMapOf<TxId, Transaction>()
        val subscribed = mutableSetOf<ByteVector32>()
        override val notifications = MutableSharedFlow<ElectrumSubscriptionResponse>(extraBufferCapacity = 64)
        override val connectionStatus: StateFlow<ElectrumConnectionStatus> = MutableStateFlow(ElectrumConnectionStatus.Closed(null))

        private fun status(scriptHash: ByteVector32): String? = unspents[scriptHash]?.takeIf { it.isNotEmpty() }?.let { "used" }

        /** Pays [script] in a fresh transaction, confirmed at [height] (0 for the mempool), and tells the wallet. */
        suspend fun pay(script: ByteVector, amount: Satoshi, height: Long = 100) {
            val tx = Transaction(
                version = 2,
                txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(Crypto.sha256(script.toByteArray() + txs.size.toByte()))), 0), ByteVector.empty, 0xffffffffL)),
                txOut = listOf(TxOut(amount, script)),
                lockTime = 0,
            )
            txs[tx.txid] = tx
            val hash = scriptHash(script)
            unspents[hash] = unspents[hash].orEmpty() + UnspentItem(tx.txid, 0, amount.sat, height)
            notifications.emit(ScriptHashSubscriptionResponse(hash, status(hash)))
        }

        override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse {
            subscribed += scriptHash
            return ScriptHashSubscriptionResponse(scriptHash, status(scriptHash))
        }

        override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = unspents[scriptHash].orEmpty()
        override suspend fun getTx(txId: TxId): Transaction? = txs[txId]
        override suspend fun getHeader(blockHeight: Int): BlockHeader? = null
        override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> = emptyList()
        override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? = null
        override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = emptyList()
        override suspend fun broadcastTransaction(tx: Transaction): TxId = tx.txid
        override suspend fun estimateFees(confirmations: Int): FeeratePerKw? = null
        override suspend fun startHeaderSubscription() = HeaderSubscriptionResponse(0, Block.RegtestGenesisBlock.header)

        companion object {
            /** Electrum's script hash: sha256 of the script, byte-reversed. */
            fun scriptHash(script: ByteVector): ByteVector32 = ByteVector32(Crypto.sha256(script.toByteArray()).reversedArray())
        }
    }

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
}
