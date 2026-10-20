package com.machankura.bennu.compose.ui.view.state


sealed interface MetadataEventDetailUIState {
    data class Loaded(
        val isActiveUser: Boolean,
        val followingActiveUserConnection: com.machankura.bennu.compose.database.model.Connection?,
        val followedByActiveUserConnection: com.machankura.bennu.compose.database.model.Connection?,
    ): MetadataEventDetailUIState

    data object Error: MetadataEventDetailUIState
    data object Loading: MetadataEventDetailUIState
}

