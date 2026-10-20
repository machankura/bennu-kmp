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
import com.machankura.bennu.compose.ui.view.state.ChapterDetailUIState

class ChapterDetailViewModel(
    val chapterId: String,
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialChapterDetailUIState: ChapterDetailUIState,
    val mantraRepository: MantraRepository,
): ViewModel() {

    var chapterDetailUIState: ChapterDetailUIState by mutableStateOf(initialChapterDetailUIState)
        private set

    private val logger = Logger.withTag(TAG)

    fun initiateChapterDetail() {
        logger.d("initiateChapterDetail: $chapterId")
        viewModelScope.launch(Dispatchers.IO) {
            val chapter = mantraRepository.getChapter(chapterId)

            chapterDetailUIState = if (chapter == null) {
                ChapterDetailUIState.Error("Couldn't find the chapter")
            } else {
                ChapterDetailUIState.Loaded(
                    chapter = chapter,
                    chunks = mantraRepository.getChunksForChapter(chapterId),
                )
            }
        }
    }

    companion object {
        private const val TAG = "ChapterDetailViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chapterId: String,
            chatRoomId: String,
            relayHint: String?,
            initialChapterDetailUIState: ChapterDetailUIState = ChapterDetailUIState.Loading,
            mantraRepository: MantraRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChapterDetailViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chapterId = chapterId,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialChapterDetailUIState = initialChapterDetailUIState,
                    mantraRepository = mantraRepository,
                )
            }
        }
    }
}
