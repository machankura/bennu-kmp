package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * What the group has said publicly, as itself, newest first, with a broadcast
 * under each post and the way into the composer for a member holding a share
 * of the key.
 */
@Serializable
data class GroupPostsRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
