package com.machankura.bennu.compose.wallet

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ElectrumMiniWallet
import fr.acinq.lightning.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.utils.sum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * A group's wallet on chain: the counterpart of Phoenix's `FinalWallet`, watching
 * the addresses [account] derives.
 *
 * Phoenix's final wallet watches one address forever. A group hands addresses out
 * from several devices and keeps change, so this watches both BIP44 branches, each
 * gap-limited -- and because an `ElectrumMiniWallet` holds one address generator,
 * that is two of them. Both share the identity's Electrum client, which is safe:
 * each ignores script hashes it does not know.
 *
 * In memory, as Phoenix's is. Everything it knows is fetched again from Electrum
 * when it starts, and nothing about the wallet is stored -- the addresses are a
 * derivation, and the coins are on chain.
 */
class GroupChainWallet(
    val account: GroupWalletDerivation.Account,
    electrum: IElectrumClient,
    scope: CoroutineScope,
    logger: Logger = Logger.withTag("GroupChainWallet"),
) {
    private val receive = ElectrumMiniWallet(account.chain.chainHash, electrum, scope, logger, lookAhead = RECEIVE_LOOKAHEAD)
    private val change = ElectrumMiniWallet(account.chain.chainHash, electrum, scope, logger, lookAhead = CHANGE_LOOKAHEAD)

    private val tip = MutableStateFlow((electrum.connectionStatus.value as? ElectrumConnectionStatus.Connected)?.height)

    /** The best block this wallet has heard of, or null before Electrum has connected. */
    val tipHeight: StateFlow<Int?> = tip.asStateFlow()

    init {
        receive.addAddressGenerator { index -> addressAt(GroupWalletDerivation.RECEIVE, index) }
        change.addAddressGenerator { index -> addressAt(GroupWalletDerivation.CHANGE, index) }

        // The tip, for confirmations. A connection reports the height it found and
        // every new block arrives as a header notification on the shared client.
        scope.launch {
            electrum.connectionStatus.filterIsInstance<ElectrumConnectionStatus.Connected>().collect { connected ->
                tip.value = maxOf(tip.value ?: 0, connected.height)
            }
        }
        scope.launch {
            electrum.notifications.filterIsInstance<HeaderSubscriptionResponse>().collect { header ->
                tip.value = maxOf(tip.value ?: 0, header.blockHeight)
            }
        }
    }

    /** Both branches and the tip, as one value. */
    val state: Flow<GroupWalletState> =
        combine(receive.walletStateFlow, change.walletStateFlow, tip) { receiveState, changeState, height ->
            GroupWalletState(account, receiveState, changeState, height)
        }

    fun stop() {
        receive.stop()
        change.stop()
    }

    private fun addressAt(change: Long, index: Int): String =
        GroupWalletDerivation.derive(account, change, index.toLong()).address(account.chain)

    companion object {
        /**
         * Receive addresses watched past the first unused one. Phoenix's swap-in
         * wallet uses 3; a group has several devices showing "the next address",
         * so two members can each hand one out before either is paid.
         */
        const val RECEIVE_LOOKAHEAD: UInt = 10u

        /** Change addresses are only ever chosen by a spend, one at a time. */
        const val CHANGE_LOOKAHEAD: UInt = 3u
    }
}

/**
 * One coin the group holds, and where under the account it is.
 *
 * [derivation] is what a spend needs to sign it -- the cache -- and what a signer
 * checks the coin's script against.
 */
data class GroupUtxo(
    val utxo: WalletState.Utxo,
    val derivation: GroupWalletDerivation.Derived,
) {
    val amount: Satoshi get() = utxo.amount
    val isConfirmed: Boolean get() = utxo.blockHeight > 0
}

/**
 * The group's wallet at one moment: both branches' coins, the next addresses to
 * use, and how deep each coin is.
 */
data class GroupWalletState(
    val account: GroupWalletDerivation.Account,
    val receive: WalletState,
    val change: WalletState,
    val tipHeight: Int?,
) {
    /** Every coin, receive and change alike, with the derivation that spends it. */
    val utxos: List<GroupUtxo> by lazy {
        listOf(GroupWalletDerivation.RECEIVE to receive, GroupWalletDerivation.CHANGE to change).flatMap { (branch, state) ->
            state.utxos.mapNotNull { utxo ->
                val index = utxo.addressMeta.indexOrNull ?: return@mapNotNull null
                GroupUtxo(utxo, GroupWalletDerivation.derive(account, branch, index.toLong()))
            }
        }
    }

    val confirmedBalance: Satoshi get() = utxos.filter { it.isConfirmed }.map { it.amount }.sum()

    /** In the mempool: the group's, but not yet spendable by the send-all builder. */
    val pendingBalance: Satoshi get() = utxos.filterNot { it.isConfirmed }.map { it.amount }.sum()

    /**
     * The address to hand out, or null until Electrum has said which are used.
     *
     * Null rather than index 0 on purpose: before the first answer every address
     * reads as unused, and showing `/0/0` to a group that has been paid there
     * fifty times would be address reuse with a straight face.
     */
    val receiveAddress: String? get() = receive.firstUnusedDerivedAddress?.first

    /** The change index a spend should pay back to, or null until Electrum has answered. */
    val nextChangeIndex: Long? get() = change.firstUnusedDerivedAddress?.second?.index?.toLong()

    /** How many confirmations [utxo] has: 0 in the mempool, and 0 until the tip is known. */
    fun confirmations(utxo: GroupUtxo): Int {
        val height = tipHeight ?: return 0
        val confirmedAt = utxo.utxo.blockHeight.toInt()
        return if (confirmedAt == 0) 0 else height - confirmedAt + 1
    }

    /**
     * Where under the account [script] is, among the addresses this wallet has
     * generated, or null if it is not one of them.
     *
     * The generated range is the bound on how far a check of somebody else's
     * claim will look: a derivation the wallet has never watched is not one it
     * will vouch for.
     */
    fun derivationOf(script: ByteVector): GroupWalletDerivation.Derived? =
        listOf(GroupWalletDerivation.RECEIVE to receive, GroupWalletDerivation.CHANGE to change)
            .asSequence()
            .flatMap { (branch, state) ->
                state.addresses.values.asSequence().mapNotNull { it.meta.indexOrNull }.map { branch to it.toLong() }
            }
            .map { (branch, index) -> GroupWalletDerivation.derive(account, branch, index) }
            .firstOrNull { it.pubkeyScript == script }
}
