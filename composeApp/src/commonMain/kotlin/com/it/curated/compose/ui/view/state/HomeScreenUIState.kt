package com.it.curated.compose.ui.view.state


sealed interface HomeScreenUIState {
    data class Loaded(
        val profileWithFollowing: com.it.curated.compose.database.model.intermdiate.LocalProfileWithFollowing
    ): HomeScreenUIState

    data object Error: HomeScreenUIState
    data object Loading: HomeScreenUIState
}

