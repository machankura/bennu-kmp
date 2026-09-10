package to.curare.compose.ui.view.state

import to.curare.compose.database.model.MantraChapter
import to.curare.compose.database.model.MantraChunk

sealed interface ChapterDetailUIState {
    data class Loaded(
        val chapter: MantraChapter,
        val chunks: List<MantraChunk> = emptyList(),
    ): ChapterDetailUIState

    data class Error(
        val message: String
    ): ChapterDetailUIState

    data object Loading: ChapterDetailUIState
}
