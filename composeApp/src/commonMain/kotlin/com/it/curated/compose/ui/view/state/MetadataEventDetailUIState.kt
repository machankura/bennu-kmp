package com.it.curated.compose.ui.view.state


sealed interface MetadataEventDetailUIState {
    data class Loaded(
        val isActiveUser: Boolean,
        val followingActiveUserConnection: com.it.curated.compose.database.model.Connection?,
        val followedByActiveUserConnection: com.it.curated.compose.database.model.Connection?,
    ): MetadataEventDetailUIState

    data object Error: MetadataEventDetailUIState
    data object Loading: MetadataEventDetailUIState
}

