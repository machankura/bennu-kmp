package to.curare.compose.ui.view.state


sealed interface HomeScreenUIState {
    data class Loaded(
        val profileWithFollowing: to.curare.compose.database.model.intermdiate.LocalProfileWithFollowing
    ): HomeScreenUIState

    data object Error: HomeScreenUIState
    data object Loading: HomeScreenUIState
}

