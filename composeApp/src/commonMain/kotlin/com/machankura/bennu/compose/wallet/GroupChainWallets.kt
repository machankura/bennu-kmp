package com.machankura.bennu.compose.wallet

import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The group wallets one identity is watching, on that identity's Electrum client.
 *
 * One per identity, made where the identity's node is, and stopped with it: a
 * profile switch is a restart, and a wallet left watching on the last profile's
 * connection would be a wallet nobody can see subscribing to addresses nobody
 * asked about. Wallets are made on first use -- opening a group's wallet screen
 * -- and kept for the life of the identity, because an Electrum subscription is
 * cheap and the first answer is slow.
 */
class GroupChainWallets(
    private val electrum: IElectrumClient,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val wallets = mutableMapOf<String, GroupChainWallet>()

    /** The wallet watching [account], made the first time it is asked for. Keyed by xpub, which names one account of one group on one network. */
    suspend fun walletFor(account: GroupWalletDerivation.Account): GroupChainWallet = mutex.withLock {
        wallets.getOrPut(account.xpub) { GroupChainWallet(account, electrum, scope) }
    }

    suspend fun stop() = mutex.withLock {
        wallets.values.forEach { it.stop() }
        wallets.clear()
    }
}
