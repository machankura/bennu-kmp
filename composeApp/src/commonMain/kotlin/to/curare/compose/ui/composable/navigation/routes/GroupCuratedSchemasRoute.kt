package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The lists the group curates, as the schemas it signed for them, one card per
 * list with its queue and its broadcast under it. Each card is the way into
 * that schema's editor for a member holding a share of the key, and the screen
 * ends with the way to add one.
 */
@Serializable
data class GroupCuratedSchemasRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
