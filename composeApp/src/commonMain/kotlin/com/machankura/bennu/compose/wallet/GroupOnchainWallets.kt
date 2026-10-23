package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The group wallets one identity is watching, each on the Electrum client for its network
 * ([GroupChainClients]): the identity's own node for the network it runs on, a connection of its own
 * for any other.
 *
 * One per identity, made where the identity's node is, and stopped with it: a
 * profile switch is a restart, and a wallet left watching on the last profile's
 * connection would be a wallet nobody can see subscribing to addresses nobody
 * asked about. Wallets are made on first use -- opening a group's wallet screen
 * -- and kept for the life of the identity, because an Electrum subscription is
 * cheap and the first answer is slow.
 */
class GroupOnchainWallets(
    private val clients: GroupChainClients,
    private val scope: CoroutineScope,
) {
    /** Every network on [electrum]: what the tests, whose fake client answers anything, construct. */
    constructor(electrum: IElectrumClient, scope: CoroutineScope) : this(GroupChainClients.single(electrum), scope)

    private val mutex = Mutex()
    private val wallets = mutableMapOf<String, GroupOnchainWallet>()

    /** The wallet watching [account], made the first time it is asked for. Keyed by xpub, which names one account of one group on one network. */
    suspend fun walletFor(account: GroupWalletDerivation.Account): GroupOnchainWallet = mutex.withLock {
        wallets.getOrPut(account.xpub) { GroupOnchainWallet(account, clients.clientFor(account.chain), scope) }
    }

    /**
     * A feerate to start the send form at, in sats per virtual byte: Electrum's
     * estimate for six blocks, or null when it has none. A suggestion the member
     * edits, never a value a spend is built on without them seeing it.
     */
    suspend fun suggestedFeeratePerVbyte(chain: Chain): Long? =
        clients.clientFor(chain).estimateFees(6)?.let { FeeratePerByte(it).feerate.sat }?.coerceAtLeast(1)

    suspend fun stop() = mutex.withLock {
        wallets.values.forEach { it.stop() }
        wallets.clear()
    }
}
