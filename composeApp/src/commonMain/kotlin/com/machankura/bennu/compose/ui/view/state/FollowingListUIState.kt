package com.machankura.bennu.compose.ui.view.state

sealed interface FollowingListUIState {
    data class Loaded(
        val following: List<com.machankura.bennu.compose.database.model.Profile>
    ): FollowingListUIState

    data object Error: FollowingListUIState
    data object Loading: FollowingListUIState
}

