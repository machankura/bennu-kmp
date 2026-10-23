package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.IcebergKeySession
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupLightningOperatorManager
import com.machankura.bennu.compose.managers.GroupLightningWalletManager

/**
 * The group's Lightning wallet screen, through every state a group passes on its way to one: no onchain
 * wallet to close to, no key, a key but no statement, and a wallet with or without an operator.
 */
sealed class GroupLightningWalletUIState {
    data object Loading : GroupLightningWalletUIState()

    data class Error(val message: String) : GroupLightningWalletUIState()

    /** The onchain wallet on the Lightning wallet's network is not open yet (decision D2). */
    data class NeedsOnchainWallet(val localChatRoom: LocalChatRoom) : GroupLightningWalletUIState()

    /** No completed Iceberg key; [ceremony] is one in progress, if any. [canMakeKey] is false for a group too small. */
    data class NeedsKey(val localChatRoom: LocalChatRoom, val ceremony: IcebergKeySession?, val canMakeKey: Boolean) : GroupLightningWalletUIState()

    /** A key, and no statement the group signed. [canPropose] is whether this device holds a share and knows the LSP. */
    data class NotOpened(val localChatRoom: LocalChatRoom, val key: IcebergKeySession, val canPropose: Boolean) : GroupLightningWalletUIState()

    data class Opened(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupLightningWalletManager.Wallet,
        val operator: GroupLightningOperatorManager.Operator?,
        /** Whether this device holds a share of the key, and so can be elected and can co-sign. */
        val isParticipant: Boolean,
        val isOperator: Boolean,
    ) : GroupLightningWalletUIState()
}
