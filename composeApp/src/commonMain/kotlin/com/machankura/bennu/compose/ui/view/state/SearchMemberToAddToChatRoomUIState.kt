package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom

sealed interface SearchMemberToAddToChatRoomUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val contacts: List<Profile>
    ): SearchMemberToAddToChatRoomUIState

    data class Error(
        val message: String
    ): SearchMemberToAddToChatRoomUIState
    data object Loading: SearchMemberToAddToChatRoomUIState
}

