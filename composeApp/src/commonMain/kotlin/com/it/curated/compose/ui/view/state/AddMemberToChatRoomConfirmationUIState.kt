package com.it.curated.compose.ui.view.state

import com.it.curated.compose.database.model.MarmotKeyPackage
import com.it.curated.compose.database.model.Profile
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom

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

