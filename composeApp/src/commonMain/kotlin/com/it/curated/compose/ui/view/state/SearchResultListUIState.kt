package com.it.curated.compose.ui.view.state

sealed interface SearchResultListUIState {
    data class Loaded(
        val localNostrEvents: List<com.it.curated.compose.database.model.intermdiate.LocalNostrEvent>
    ): SearchResultListUIState

    data object Error: SearchResultListUIState
    data object Loading: SearchResultListUIState
}

