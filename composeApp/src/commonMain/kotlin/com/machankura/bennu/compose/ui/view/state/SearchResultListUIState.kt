package com.machankura.bennu.compose.ui.view.state

sealed interface SearchResultListUIState {
    data class Loaded(
        val localNostrEvents: List<com.machankura.bennu.compose.database.model.intermdiate.LocalNostrEvent>
    ): SearchResultListUIState

    data object Error: SearchResultListUIState
    data object Loading: SearchResultListUIState
}

