package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.MarmotKeyPackage
import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom

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

