package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class KeyRecoveryRoute(
    val activeUserPublicKey: String,
): Route()
