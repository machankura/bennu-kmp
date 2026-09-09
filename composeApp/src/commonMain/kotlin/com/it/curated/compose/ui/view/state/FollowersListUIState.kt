package com.it.curated.compose.ui.view.state

sealed interface FollowersListUIState {
    data class Loaded(
        val followers: List<com.it.curated.compose.database.model.Profile>
    ): FollowersListUIState

    data object Error: FollowersListUIState
    data object Loading: FollowersListUIState
}

