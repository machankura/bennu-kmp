package to.curare.compose.ui.view.state

import to.curare.compose.database.model.MantraChunk
import to.curare.compose.database.model.intermdiate.LocalChatRoom

sealed interface TranslateChunkUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val originalChunk: MantraChunk,
        val existingTranslationText: String = "",

        /**
         * Whether the group holds a shared key. A translation is signed into
         * existence now rather than saved, so a group without one cannot
         * translate a chunk here at all.
         */
        val canSign: Boolean = false,
    ): TranslateChunkUIState

    data class Error(
        val message: String
    ): TranslateChunkUIState

    data object Loading: TranslateChunkUIState
}
