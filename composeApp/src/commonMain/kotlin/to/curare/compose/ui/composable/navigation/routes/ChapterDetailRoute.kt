package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class ChapterDetailRoute(
    val activeUserPublicKey: String,
    val chapterId: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()
