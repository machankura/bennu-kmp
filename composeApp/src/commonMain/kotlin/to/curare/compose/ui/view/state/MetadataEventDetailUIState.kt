package to.curare.compose.ui.view.state


sealed interface MetadataEventDetailUIState {
    data class Loaded(
        val isActiveUser: Boolean,
        val followingActiveUserConnection: to.curare.compose.database.model.Connection?,
        val followedByActiveUserConnection: to.curare.compose.database.model.Connection?,
    ): MetadataEventDetailUIState

    data object Error: MetadataEventDetailUIState
    data object Loading: MetadataEventDetailUIState
}

