package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class LoadingRoute(
    val text: String? = null
): Route()