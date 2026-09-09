package com.it.curated.compose.ui.view.state

import com.it.curated.compose.database.model.Profile

sealed interface SelectChatRoomMembersUIState {
    data class Loaded(
        val profiles: List<Profile>
    ): SelectChatRoomMembersUIState

    data class Error(
        val message: String
    ): SelectChatRoomMembersUIState

    data object Loading: SelectChatRoomMembersUIState
}
