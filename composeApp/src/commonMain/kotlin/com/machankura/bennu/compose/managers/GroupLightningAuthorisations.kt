package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.IcebergBudgetSpend
import com.machankura.bennu.compose.nostr.lightning.GroupLightningWalletStatementEvent
import fr.acinq.bitcoin.ByteVector32

/**
 * What the group has authorised its Lightning wallet to spend, as a co-signer reads it
 * (`docs/group-lightning-wallet.md`, Phases 12 and 13).
 *
 * An authorisation is a group-signed event (kind 30346). Until Phase 12 there are none, so a co-signer
 * lets no outgoing payment through and signs no force close: the wallet can receive, and nothing else.
 */
object GroupLightningAuthorisations {
    /** What each authorised payment has left to spend, by payment hash, in millisatoshi. */
    suspend fun budgets(database: BennuDatabase, wallet: GroupLightningWalletManager.Wallet): Map<String, Long> {
        val key = GroupLightningCoSigner.walletKey(wallet.signedEvent.chatRoomId, wallet.statement.chain, wallet.statement.wallet)
        val spent = database.icebergCoSigningDao().budgetSpends(key).associate { it.paymentHash to it.consumedMsat }
        return authorisedPayments(database, wallet).mapValues { (hash, amount) -> amount - (spent[hash] ?: 0) }.filterValues { it > 0 }
    }

    /** Records that a commitment spent [consumed] of each authorisation, by payment hash. */
    suspend fun consume(database: BennuDatabase, wallet: GroupLightningWalletManager.Wallet, consumed: Map<String, Long>) {
        if (consumed.isEmpty()) return
        val key = GroupLightningCoSigner.walletKey(wallet.signedEvent.chatRoomId, wallet.statement.chain, wallet.statement.wallet)
        val dao = database.icebergCoSigningDao()
        val spent = dao.budgetSpends(key).associate { it.paymentHash to it.consumedMsat }
        consumed.forEach { (hash, amount) -> dao.upsertBudgetSpend(IcebergBudgetSpend(key, hash, (spent[hash] ?: 0) + amount)) }
    }

    /** Every payment the group has authorised, with its whole budget: none until Phase 12. */
    suspend fun authorisedPayments(database: BennuDatabase, wallet: GroupLightningWalletManager.Wallet): Map<String, Long> = emptyMap()

    /** Whether the group has authorised a force close of [channelId]: never, until Phase 13. */
    suspend fun forceCloseAuthorised(database: BennuDatabase, chatRoomId: String, statement: GroupLightningWalletStatementEvent.Statement, channelId: ByteVector32): Boolean = false
}
