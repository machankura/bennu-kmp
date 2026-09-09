package com.it.curated.compose.ui.view.state

import com.it.curated.compose.database.model.Profile

sealed interface SelectChatRoomTypeUIState {
    data class Loaded(
        /** The picked members, for naming them back to the user. */
        val members: List<Profile>
    ): SelectChatRoomTypeUIState

    data class Error(
        val message: String
    ): SelectChatRoomTypeUIState

    data object Loading: SelectChatRoomTypeUIState
}
