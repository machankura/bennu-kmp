package to.curare.compose.ui.view.state

sealed interface SearchUIState {
    data class Loaded(
        val localNostrEvents: List<to.curare.compose.database.model.intermdiate.LocalNostrEvent>
    ): SearchUIState

    data object Error: SearchUIState
    data object Prompt: SearchUIState

    data object LandingSearch: SearchUIState
}

