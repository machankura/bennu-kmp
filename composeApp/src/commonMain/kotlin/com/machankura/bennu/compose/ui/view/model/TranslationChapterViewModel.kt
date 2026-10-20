package com.machankura.bennu.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.ui.view.state.ChunkTranslationPair
import com.machankura.bennu.compose.ui.view.state.TranslationChapterUIState

class TranslationChapterViewModel(
    val translationChapterId: String,
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialTranslationChapterUIState: TranslationChapterUIState,
    val mantraRepository: MantraRepository,
): ViewModel() {

    var translationChapterUIState: TranslationChapterUIState by mutableStateOf(initialTranslationChapterUIState)
        private set

    private val logger = Logger.withTag(TAG)

    fun initiateTranslationChapter() {
        logger.d("initiateTranslationChapter: $translationChapterId")
        viewModelScope.launch(Dispatchers.IO) {
            val translationChapter = mantraRepository.getTranslationChapter(translationChapterId)
            val sourceChapter = translationChapter?.let { mantraRepository.getChapter(it.chapterId) }

            translationChapterUIState = if (translationChapter == null || sourceChapter == null) {
                TranslationChapterUIState.Error("Couldn't find the translation chapter")
            } else {
                // Original dialect: source chapter -> version -> artifact -> dialect.
                val originalDialectName = mantraRepository.getArtifactVersion(sourceChapter.artifactVersionId)
                    ?.let { mantraRepository.getArtifact(it.artifactId) }
                    ?.let { mantraRepository.getDialect(it.dialectId) }
                    ?.name ?: "Original"

                // Translation dialect: translation version -> dialect.
                val translationDialectName = mantraRepository.getTranslation(translationChapter.translationArtifactVersionId)
                    ?.let { mantraRepository.getDialect(it.dialectId) }
                    ?.name ?: "Translation"

                // Pair each original chunk with its translation chunk (by chunkId).
                val translationChunksByChunkId = mantraRepository
                    .getTranslationChunks(translationChapter.id)
                    .associateBy { it.chunkId }

                val rows = mantraRepository.getChunksForChapter(sourceChapter.id).map { originalChunk ->
                    ChunkTranslationPair(
                        originalChunk = originalChunk,
                        translationChunk = translationChunksByChunkId[originalChunk.id],
                    )
                }

                TranslationChapterUIState.Loaded(
                    originalDialectName = originalDialectName,
                    translationDialectName = translationDialectName,
                    rows = rows,
                )
            }
        }
    }

    companion object {
        private const val TAG = "TranslationChapterViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            translationChapterId: String,
            chatRoomId: String,
            relayHint: String?,
            initialTranslationChapterUIState: TranslationChapterUIState = TranslationChapterUIState.Loading,
            mantraRepository: MantraRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TranslationChapterViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    translationChapterId = translationChapterId,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialTranslationChapterUIState = initialTranslationChapterUIState,
                    mantraRepository = mantraRepository,
                )
            }
        }
    }
}
