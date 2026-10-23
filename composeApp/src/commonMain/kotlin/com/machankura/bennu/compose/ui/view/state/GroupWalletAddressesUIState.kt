package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.wallet.GroupAddress

sealed interface GroupWalletAddressesUIState {
    /**
     * The wallet's addresses as the chain last said.
     *
     * [receive] is null until Electrum has said which are used: before that every
     * address reads as unused, and a list that called `/0/0` current would be
     * wrong about a group paid there before.
     */
    data class Loaded(
        val receive: List<GroupAddress>?,
        /** The change addresses that have been used, newest first: empty for a wallet that has never sent. */
        val change: List<GroupAddress> = emptyList(),
    ): GroupWalletAddressesUIState

    /**
     * A profile with no node of its own -- an nsec or an npub -- which knows the
     * group keeps a wallet and cannot see which of its addresses are used.
     */
    data object CannotWatch: GroupWalletAddressesUIState

    data class Error(
        val message: String
    ): GroupWalletAddressesUIState

    data object Loading: GroupWalletAddressesUIState
}
