package to.curare.compose.ui.view.state

import to.curare.compose.database.model.MantraDialect
import to.curare.compose.database.model.intermdiate.LocalChatRoom

sealed interface AddArtifactUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val dialects: List<MantraDialect> = emptyList(),

        /**
         * Whether the group holds a shared key. An artifact is signed into
         * existence now rather than submitted, so a group without one cannot
         * add one here at all.
         */
        val canSign: Boolean = false,
    ): AddArtifactUIState

    data class Error(
        val message: String
    ): AddArtifactUIState

    data object Loading: AddArtifactUIState
}
