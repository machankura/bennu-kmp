package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.wallet.GroupWalletState

sealed interface GroupWalletUIState {
    /**
     * A room whose group has not signed a wallet statement.
     *
     * [account] is what a statement would carry, or null for a room with no key
     * at all -- there is nothing to open then. [canSign] gates the way in, for
     * the reason every proposing screen gives: `proposeSigning` throws for a
     * device holding no share.
     */
    data class NotOpened(
        val localChatRoom: LocalChatRoom,
        val account: GroupWalletDerivation.Account?,
        val canSign: Boolean,
    ): GroupWalletUIState

    /**
     * An open wallet.
     *
     * [walletState] is null in two different situations, told apart by
     * [canWatch]: before Electrum's first answer, and for a profile with no node
     * to watch the chain with at all.
     */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupWallet,
        val walletState: GroupWalletState?,
        val canWatch: Boolean,
        val canSign: Boolean,
    ): GroupWalletUIState

    data class Error(
        val message: String
    ): GroupWalletUIState

    data object Loading: GroupWalletUIState
}
