package com.machankura.bennu.compose.ui.view.state

sealed interface ChatRoomListUIState {
    data class Loaded(
        val chatRoomList: List<com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom>
    ): ChatRoomListUIState

    data object Error: ChatRoomListUIState
    data object Loading: ChatRoomListUIState
}

