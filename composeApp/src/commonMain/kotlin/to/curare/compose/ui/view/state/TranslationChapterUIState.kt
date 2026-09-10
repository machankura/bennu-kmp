package to.curare.compose.ui.view.state

import to.curare.compose.database.model.MantraChunk
import to.curare.compose.database.model.MantraTranslationChunk

/**
 * An original chunk paired with its translation chunk. [translationChunk] is
 * null (or has blank text) when the chunk hasn't been translated yet, in which
 * case the UI shows the original text as a greyed-out placeholder.
 */
data class ChunkTranslationPair(
    val originalChunk: MantraChunk,
    val translationChunk: MantraTranslationChunk?,
)

sealed interface TranslationChapterUIState {
    data class Loaded(
        val originalDialectName: String,
        val translationDialectName: String,
        val rows: List<ChunkTranslationPair> = emptyList(),
    ): TranslationChapterUIState

    data class Error(
        val message: String
    ): TranslationChapterUIState

    data object Loading: TranslationChapterUIState
}
