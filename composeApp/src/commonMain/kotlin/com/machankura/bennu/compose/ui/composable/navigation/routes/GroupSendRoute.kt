package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The form a member proposes a spend from the group's wallet with. */
@Serializable
data class GroupSendRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?,
    /** The wallet's network, as `GroupWalletStatementEvent.chainName` spells it; null for the node's own. */
    val chain: String? = null,
): Route()
