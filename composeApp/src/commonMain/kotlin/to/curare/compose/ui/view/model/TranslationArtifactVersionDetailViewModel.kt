package to.curare.compose.ui.view.model

import androidx.compose.runtime.MutableState
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
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import to.curare.compose.database.model.MantraChapter
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.managers.FrostSigningManager
import to.curare.compose.managers.TranslationScaffold
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.repository.MantraRepository
import to.curare.compose.ui.view.state.TranslationChapterProgress
import to.curare.compose.ui.view.state.TranslationArtifactVersionDetailUIState

class TranslationArtifactVersionDetailViewModel(
    val translationArtifactVersionId: String,
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialTranslationArtifactVersionDetailUIState: TranslationArtifactVersionDetailUIState,
    val mantraRepository: MantraRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var translationDetailUIState: TranslationArtifactVersionDetailUIState by mutableStateOf(initialTranslationArtifactVersionDetailUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateTranslationArtifactVersionDetail() {
        logger.d("initiateTranslationArtifactVersionDetail: $translationArtifactVersionId")
        viewModelScope.launch(Dispatchers.IO) {
            val translation = mantraRepository.getTranslation(translationArtifactVersionId)

            translationDetailUIState = if (translation == null) {
                TranslationArtifactVersionDetailUIState.Error("Couldn't find the translation")
            } else {
                val translationChapters = mantraRepository.getTranslationChapters(translationArtifactVersionId)
                val chapters = translationChapters.map { chapter ->
                    val chunks = mantraRepository.getTranslationChunks(chapter.id)
                    TranslationChapterProgress(
                        chapter = chapter,
                        totalChunks = chunks.size,
                        translatedChunks = chunks.count { it.text.isNotBlank() },
                    )
                }

                TranslationArtifactVersionDetailUIState.Loaded(
                    translation = translation,
                    chapters = chapters,
                    localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId),
                    missingChapters = TranslationScaffold.chaptersMissingFrom(
                        translationChapters = translationChapters,
                        sourceChapters = mantraRepository
                            .getChaptersForArtifactVersion(translation.artifactVersionId),
                    ),
                    canSign = frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Asks the group to put the chapters this translation is missing into it.
     *
     * The scaffolding a chapter is signed with covers the translations that
     * existed when it was proposed, and the scaffolding a translation is signed
     * with covers the chapters that existed when *it* was proposed. Two things
     * proposed at the same moment see neither the other, and a session that
     * fails to reach a quorum leaves nothing behind to try again with. Neither
     * is a bug that can be designed out of a snapshot taken at proposal time --
     * so this is the pass that reconciles them, run when somebody notices.
     *
     * It proposes only what is missing, matched on the source chapter each
     * translation chapter names, so running it on a translation that is already
     * complete proposes nothing rather than a second copy of everything.
     */
    fun catchUpMissingChapters(
        localChatRoom: LocalChatRoom,
        missingChapters: List<MantraChapter>,
        onSuccess: (sessionId: String) -> Unit,
        onFailure: () -> Unit
    ) {
        if (missingChapters.isEmpty()) {
            onFailure.invoke()
            return
        }

        // Guard against double submits from repeated taps.
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // One timestamp for the lot, so a catch-up reads as the one act it
            // is rather than as chapters that happen to share a minute.
            val createdAt = TimeUtils.now()

            val sessions = runCatching {
                // One session per MAX_BATCH_SIZE chapters. A translation far
                // enough behind to need more than a batch holds is not one to
                // refuse; it is one the group answers for more than once.
                missingChapters.chunked(FrostSigningManager.MAX_BATCH_SIZE).mapNotNull { batch ->
                    frostSigningRepository.proposeSigningBatch(
                        localChatRoom = localChatRoom,
                        userPublicKey = activeUserPublicKey,
                        events = TranslationScaffold.chaptersOf(
                            translationArtifactVersionIds = listOf(translationArtifactVersionId),
                            chapters = batch.map(TranslationScaffold.SourceChapter::of),
                            createdAt = createdAt,
                        )
                    )
                }
            }.onFailure { error ->
                logger.e("Failed to propose this translation's missing chapters", error)
            }.getOrNull().orEmpty()

            // The first, because it is the one that opened; the rest are in the
            // room's session list beside it.
            val session = sessions.firstOrNull()

            viewModelScope.launch(Dispatchers.Main) {
                if (session != null) onSuccess.invoke(session.id) else onFailure.invoke()
            }

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "TranslationArtifactVersionDetailViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            translationArtifactVersionId: String,
            chatRoomId: String,
            relayHint: String?,
            initialTranslationArtifactVersionDetailUIState: TranslationArtifactVersionDetailUIState = TranslationArtifactVersionDetailUIState.Loading,
            mantraRepository: MantraRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TranslationArtifactVersionDetailViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    translationArtifactVersionId = translationArtifactVersionId,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialTranslationArtifactVersionDetailUIState = initialTranslationArtifactVersionDetailUIState,
                    mantraRepository = mantraRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                )
            }
        }
    }
}
