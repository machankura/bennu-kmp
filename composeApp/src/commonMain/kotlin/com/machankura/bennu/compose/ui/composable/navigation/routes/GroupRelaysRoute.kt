package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Where the group has said it lives on nostr: its four relay lists, as the
 * group signed them, with a broadcast under each one it has agreed and the way
 * into the editor for a member holding a share of the key.
 */
@Serializable
data class GroupRelaysRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
