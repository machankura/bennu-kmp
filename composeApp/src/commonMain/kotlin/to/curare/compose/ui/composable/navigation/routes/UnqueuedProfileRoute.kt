package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class UnqueuedProfileRoute(
    val nostrEventId: String,
): Route()