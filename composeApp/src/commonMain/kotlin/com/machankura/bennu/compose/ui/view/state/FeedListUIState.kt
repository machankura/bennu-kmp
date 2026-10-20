package com.machankura.bennu.compose.ui.view.state

sealed interface FeedListUIState {
    data class Loaded(
        val localNostrEvents: List<com.machankura.bennu.compose.database.model.intermdiate.LocalNostrEvent>
    ): FeedListUIState

    data object Error: FeedListUIState
    data object Loading: FeedListUIState
}

