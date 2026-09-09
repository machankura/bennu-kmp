package com.it.curated.compose.ui.view.state

import com.it.curated.compose.database.model.MantraChapter
import com.it.curated.compose.database.model.MantraChunk

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
