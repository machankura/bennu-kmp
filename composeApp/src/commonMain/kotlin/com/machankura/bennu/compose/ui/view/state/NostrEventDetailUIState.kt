package com.machankura.bennu.compose.ui.view.state

sealed interface NostrEventDetailUIState {
    data class Loaded(
        val localNostrEvent: com.machankura.bennu.compose.database.model.intermdiate.LocalNostrEvent
    ): NostrEventDetailUIState

    data object Error: NostrEventDetailUIState
    data object NotFound: NostrEventDetailUIState
    data object Loading: NostrEventDetailUIState
}

