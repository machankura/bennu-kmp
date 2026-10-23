package com.machankura.bennu.compose.wallet

import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.database.model.ChatMessage
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.FrostSigningSession
import com.machankura.bennu.compose.database.model.GroupTransaction
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.database.model.types.FrostSigningStage
import com.machankura.bennu.compose.database.model.types.FrostSigningSubject
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What happens to a signed spend after the group signed it: it is sent, sent
 * again while nobody has seen it, and -- once it confirms -- settles every other
 * spend of the same coins.
 */
class GroupTransactionWatcherJvmTest {
    private class FakeElectrum : IElectrumClient {
        val broadcasts = mutableListOf<TxId>()
        /** Confirmations by txid; absent is "Electrum has not heard of it". */
        val confirmations = mutableMapOf<TxId, Int>()

        override val notifications = MutableSharedFlow<ElectrumSubscriptionResponse>()
        override val connectionStatus: StateFlow<ElectrumConnectionStatus> = MutableStateFlow(ElectrumConnectionStatus.Closed(null))
        override suspend fun broadcastTransaction(tx: Transaction): TxId = tx.txid.also { broadcasts += it }
        override suspend fun getConfirmations(tx: Transaction): Int? = confirmations[tx.txid]
        override suspend fun getTx(txId: TxId): Transaction? = null
        override suspend fun getHeader(blockHeight: Int): BlockHeader? = null
        override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> = emptyList()
        override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? = null
        override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = emptyList()
        override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = emptyList()
        override suspend fun estimateFees(confirmations: Int): FeeratePerKw? = null
        override suspend fun startScriptHashSubscription(scriptHash: ByteVector32) = ScriptHashSubscriptionResponse(scriptHash, null)
        override suspend fun startHeaderSubscription() = HeaderSubscriptionResponse(0, Block.RegtestGenesisBlock.header)
    }

    private val db: BennuDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())
    private val electrum = FakeElectrum()
    private var clock = Instant.fromEpochSeconds(1_700_000_000)
    private val watcher = GroupTransactionWatcher(db, electrum, CoroutineScope(Dispatchers.Default), now = { clock })

    @AfterTest
    fun close() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val room = "11".repeat(32)

    /** The coin every spend below wants. */
    private val coin = OutPoint(TxId(ByteVector32(ByteArray(32) { 3 })), 0)

    private fun spending(vararg coins: OutPoint, pay: Long) = Transaction(
        2, coins.map { TxIn(it, ByteVector.empty, 0xfffffffdL) }, listOf(TxOut(Satoshi(pay), ByteVector("0014" + "00".repeat(20)))), 0,
    )

    private suspend fun seed() {
        val eventId = "c".repeat(64)
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = eventId))
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = user, subject = null, description = null, mlsGroupState = "00"))
    }

    private suspend fun spend(id: String, tx: Transaction, stage: FrostSigningStage, signed: Boolean, chain: String = "regtest") {
        db.frostSigningSessionDao().upsert(
            FrostSigningSession(
                id = id, chatRoomId = room, coordinatorPublicKey = user, userPublicKey = user, dkgSessionId = "dkg",
                threshold = 2, participantCount = 3, signerId = 0, stage = stage, subject = FrostSigningSubject.TRANSACTION,
            )
        )
        db.groupTransactionDao().insertIfAbsent(
            GroupTransaction(
                frostSigningSessionId = id, chatRoomId = room, chain = chain, account = 0,
                psbt = GroupSpend.encode(Psbt(tx)), txid = tx.txid.toString(),
                signedTransaction = if (signed) Hex.encode(Transaction.write(tx)) else null,
            )
        )
    }

    @Test
    fun `each spend goes out on its own network's connection`() = runBlocking {
        // A group with wallets on two networks: the node's, and the test network a Lightning wallet is
        // tried on. Each signed spend must reach the network it spends on, and only that one.
        seed()
        val testnet = FakeElectrum()
        val clients = GroupChainClients(fr.acinq.bitcoin.Chain.Regtest, electrum) { GroupChainClients.Connection(testnet) {} }
        val watcher = GroupTransactionWatcher(db, clients, CoroutineScope(Dispatchers.Default), now = { clock })
        val onNode = spending(coin, pay = 9_000)
        val onTestnet = spending(OutPoint(TxId(ByteVector32(ByteArray(32) { 4 })), 0), pay = 8_000)
        spend("a", onNode, FrostSigningStage.COMPLETE, signed = true)
        spend("b", onTestnet, FrostSigningStage.COMPLETE, signed = true, chain = "testnet3")
        watcher.pass()
        assertEquals(listOf(onNode.txid), electrum.broadcasts)
        assertEquals(listOf(onTestnet.txid), testnet.broadcasts)
    }

    @Test
    fun `a spend on a network nobody named is left alone`() = runBlocking {
        seed()
        spend("a", spending(coin, pay = 9_000), FrostSigningStage.COMPLETE, signed = true, chain = "litecoin")
        watcher.pass()
        assertTrue(electrum.broadcasts.isEmpty())
    }

    @Test
    fun `a signed spend is sent, and sent again while nobody has seen it`() = runBlocking {
        seed()
        val tx = spending(coin, pay = 9_000)
        spend("a", tx, FrostSigningStage.COMPLETE, signed = true)

        watcher.pass()
        watcher.pass()
        assertEquals(listOf(tx.txid), electrum.broadcasts, "not again inside the interval")

        clock += 11.minutes
        watcher.pass()
        assertEquals(2, electrum.broadcasts.size, "sent again once the interval has passed, still unseen")

        electrum.confirmations[tx.txid] = 0
        watcher.pass()
        val seen = assertNotNull(db.groupTransactionDao().getBySessionId("a"))
        assertNotNull(seen.seenAt, "in the mempool is seen")
        assertNull(seen.confirmedAt)

        clock += 11.minutes
        watcher.pass()
        assertEquals(2, electrum.broadcasts.size, "a seen transaction is not sent again")
    }

    @Test
    fun `a confirmation settles every other spend of the same coins`() = runBlocking {
        seed()
        val winner = spending(coin, pay = 9_000)
        spend("winner", winner, FrostSigningStage.COMPLETE, signed = true)
        // A bump of the same coins, signed but beaten to the block.
        val replaced = spending(coin, pay = 8_000)
        spend("replaced", replaced, FrostSigningStage.COMPLETE, signed = true)
        // A third spend of the coin, still collecting signatures.
        spend("collecting", spending(coin, OutPoint(TxId(ByteVector32(ByteArray(32) { 4 })), 0), pay = 15_000), FrostSigningStage.COLLECTING_NONCES, signed = false)
        // And one of some other coin, which none of this touches.
        spend("unrelated", spending(OutPoint(TxId(ByteVector32(ByteArray(32) { 5 })), 1), pay = 1_000), FrostSigningStage.COLLECTING_NONCES, signed = false)

        electrum.confirmations[winner.txid] = 1
        watcher.pass()

        assertNotNull(db.groupTransactionDao().getBySessionId("winner")?.confirmedAt)
        assertEquals(winner.txid.toString(), db.groupTransactionDao().getBySessionId("replaced")?.replacedByTxid)
        assertEquals(FrostSigningStage.COMPLETE, db.frostSigningSessionDao().getSessionById("replaced")?.stage, "a signed spend is not reported as failed")
        db.frostSigningSessionDao().getSessionById("collecting")!!.let {
            assertEquals(FrostSigningStage.FAILED, it.stage)
            assertEquals("Spent by another transaction", it.failureReason)
        }
        assertEquals(FrostSigningStage.COLLECTING_NONCES, db.frostSigningSessionDao().getSessionById("unrelated")?.stage)

        // Once, however many passes see it.
        watcher.pass()
        val lines = db.chatMessageDao().getChatMessagesByChatRoomId(room)
            .filter { it.chatMessage.messageType == ChatMessage.TYPE_GROUP_TRANSACTION_CONFIRMED }
        assertEquals(1, lines.size)
        assertTrue(db.groupTransactionDao().getUnsettled().isEmpty(), "neither signed spend is still on its way")
    }
}
