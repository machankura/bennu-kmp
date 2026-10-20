package com.machankura.bennu.compose.ui.view.state


sealed interface HomeScreenUIState {
    data class Loaded(
        val profileWithFollowing: com.machankura.bennu.compose.database.model.intermdiate.LocalProfileWithFollowing
    ): HomeScreenUIState

    data object Error: HomeScreenUIState
    data object Loading: HomeScreenUIState
}

