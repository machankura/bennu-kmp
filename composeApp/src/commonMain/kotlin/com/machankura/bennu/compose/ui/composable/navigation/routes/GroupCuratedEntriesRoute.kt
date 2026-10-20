package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The entries the group has accepted into the lists it curates, newest first,
 * with a broadcast under each. Nothing is added from here: an entry is accepted
 * from the queue it was suggested to, which is reached from its schema's card
 * on the [GroupCuratedSchemasRoute] screen.
 */
@Serializable
data class GroupCuratedEntriesRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
