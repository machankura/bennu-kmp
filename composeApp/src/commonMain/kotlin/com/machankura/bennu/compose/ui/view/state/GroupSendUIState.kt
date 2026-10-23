package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.wallet.GroupWalletState
import fr.acinq.bitcoin.OutPoint

sealed interface GroupSendUIState {
    /**
     * A wallet this device can propose a spend from: it holds a share, and it has
     * a node to see the coins with. [walletState] is null until Electrum answers.
     */
    data class Ready(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupWallet,
        val walletState: GroupWalletState?,
        /** The coins open spends already hold, which a new one leaves alone. */
        val reserved: Set<OutPoint>,
    ): GroupSendUIState

    /** A room this device cannot propose from -- no wallet, no share, or no node. */
    data object Unavailable: GroupSendUIState

    data class Error(val message: String): GroupSendUIState

    data object Loading: GroupSendUIState
}
