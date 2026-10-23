package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * What the group says about itself on nostr, as the group signed it.
 *
 * The group's screen names the profile in a row; this is where it is read, with
 * the broadcast under it and, for a member holding a share of the key, the way
 * into the editor. One of the three screens the group's signed work is split
 * across -- see [GroupRelaysRoute] and [GroupPostsRoute] -- each pushed from its
 * row on the group's screen.
 */
@Serializable
data class GroupNostrProfileRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
