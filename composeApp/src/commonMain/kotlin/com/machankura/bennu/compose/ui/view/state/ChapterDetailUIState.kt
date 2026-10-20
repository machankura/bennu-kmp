package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.MantraChapter
import com.machankura.bennu.compose.database.model.MantraChunk

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
