package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The form a member proposes a spend from the group's wallet with -- or, with
 * [guardedChatRoomId], from that descendant's guarded wallet, through the
 * group's guardian leaf.
 */
@Serializable
data class GroupSendRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?,
    val guardedChatRoomId: String? = null,
): Route()
