package to.curare.compose.ui.view.state

import to.curare.compose.database.model.intermdiate.LocalChatMessage

sealed interface ChatMessageListUIState {
    data class Loaded(
        val chatMessageList: List<LocalChatMessage>
    ): ChatMessageListUIState

    data object Error: ChatMessageListUIState
    data object Loading: ChatMessageListUIState
}

