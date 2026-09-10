package to.curare.compose.ui.view.state

sealed interface FollowersListUIState {
    data class Loaded(
        val followers: List<to.curare.compose.database.model.Profile>
    ): FollowersListUIState

    data object Error: FollowersListUIState
    data object Loading: FollowersListUIState
}

