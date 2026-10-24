package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.MemberWalletSpend
import com.machankura.bennu.compose.managers.MemberWalletManager
import com.machankura.bennu.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.Satoshi

sealed interface MemberWalletUIState {
    data object Loading : MemberWalletUIState

    data class Error(val message: String) : MemberWalletUIState

    data class Loaded(
        val wallet: MemberWalletManager.MemberWalletRow,
        /** Null before Electrum's first answer, for a wallet no group has certified, and for a profile that cannot watch the chain. */
        val walletState: GroupWalletState? = null,
        /** Whether this profile has an Electrum client to watch the chain with. */
        val canWatch: Boolean,
        /** What the wallet's guardians took from it, read off the chain: never stored, found again on each look. */
        val taken: List<TakenLine> = emptyList(),
        /** The spends the member signed from it, newest first. */
        val spends: List<MemberWalletSpend> = emptyList(),
        /** How many groups guard it, for the line under the title. */
        val reach: Int = wallet.account.guardians.size,
    ) : MemberWalletUIState
}

/** One guardian spend from a member's wallet, named for the group that made it. */
data class TakenLine(
    val txid: String,
    /** The guardian's room name on this device, or null when this device is not in it. */
    val guardianName: String?,
    val amount: Satoshi,
)
