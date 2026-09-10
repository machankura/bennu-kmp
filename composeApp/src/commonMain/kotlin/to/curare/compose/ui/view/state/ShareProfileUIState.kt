package to.curare.compose.ui.view.state

sealed interface ShareProfileUIState {
    data class Loaded(
        val localNostrEvent: to.curare.compose.database.model.intermdiate.LocalNostrEvent
    ): ShareProfileUIState

    data object Error: ShareProfileUIState
    data object NotFound: ShareProfileUIState
    data object Loading: ShareProfileUIState
}

