package to.curare.compose.ui.view.state

import to.curare.compose.database.model.MarmotKeyPackage
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom

sealed interface AddMemberToChatRoomConfirmationUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val profile: Profile,
        val marmotKeyPackage: MarmotKeyPackage
    ): AddMemberToChatRoomConfirmationUIState

    data class Error(
        val message: String
    ): AddMemberToChatRoomConfirmationUIState
    data object Loading: AddMemberToChatRoomConfirmationUIState
}

