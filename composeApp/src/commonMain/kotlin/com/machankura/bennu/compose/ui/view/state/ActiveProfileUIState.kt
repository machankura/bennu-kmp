package com.machankura.bennu.compose.ui.view.state

sealed interface ActiveProfileUIState {
    data class Loaded(
        val localNostrEvent: com.machankura.bennu.compose.database.model.intermdiate.LocalNostrEvent
    ): ActiveProfileUIState

    data object Error: ActiveProfileUIState
    data object NotFound: ActiveProfileUIState
    data object Loading: ActiveProfileUIState
}

