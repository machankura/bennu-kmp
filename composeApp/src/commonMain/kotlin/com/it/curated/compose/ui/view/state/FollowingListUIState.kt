package com.it.curated.compose.ui.view.state

sealed interface FollowingListUIState {
    data class Loaded(
        val following: List<com.it.curated.compose.database.model.Profile>
    ): FollowingListUIState

    data object Error: FollowingListUIState
    data object Loading: FollowingListUIState
}

