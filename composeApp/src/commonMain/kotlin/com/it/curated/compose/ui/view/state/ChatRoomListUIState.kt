package com.it.curated.compose.ui.view.state

sealed interface ChatRoomListUIState {
    data class Loaded(
        val chatRoomList: List<com.it.curated.compose.database.model.intermdiate.LocalChatRoom>
    ): ChatRoomListUIState

    data object Error: ChatRoomListUIState
    data object Loading: ChatRoomListUIState
}

