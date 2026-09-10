package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class EditGroupNostrProfileRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
