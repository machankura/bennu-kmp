package com.it.curated.compose.ui.view.state

sealed interface FeedListUIState {
    data class Loaded(
        val localNostrEvents: List<com.it.curated.compose.database.model.intermdiate.LocalNostrEvent>
    ): FeedListUIState

    data object Error: FeedListUIState
    data object Loading: FeedListUIState
}

