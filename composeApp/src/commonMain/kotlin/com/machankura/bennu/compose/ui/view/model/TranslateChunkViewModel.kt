package com.machankura.bennu.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import com.machankura.bennu.compose.database.model.MantraChunk
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.nip30303.TranslationChunkEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.ui.view.state.TranslateChunkUIState

class TranslateChunkViewModel(
    val translationChapterId: String,
    val chunkId: String,
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialTranslateChunkUIState: TranslateChunkUIState,
    val mantraRepository: MantraRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var translateChunkUIState: TranslateChunkUIState by mutableStateOf(initialTranslateChunkUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateTranslateChunk() {
        viewModelScope.launch(Dispatchers.IO) {
            val originalChunk = mantraRepository.getChunk(chunkId)
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            translateChunkUIState = when {
                originalChunk == null -> TranslateChunkUIState.Error("Couldn't find the chunk")
                localChatRoom == null -> TranslateChunkUIState.Error("Couldn't find the chat room")
                else -> TranslateChunkUIState.Loaded(
                    localChatRoom = localChatRoom,
                    originalChunk = originalChunk,
                    // What the group has already signed for this chunk, so a
                    // retranslation starts from it rather than from nothing.
                    existingTranslationText = mantraRepository
                        .getTranslationChunks(translationChapterId)
                        .firstOrNull { it.chunkId == chunkId }
                        ?.text
                        .orEmpty(),
                    canSign = frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Asks the group to sign this chunk's translation.
     *
     * The translation is not saved here and does not exist yet. What goes out is
     * a proposal to sign it, and the translated chunk appears -- on every
     * member's device at once, authored by this room's own key rather than by
     * whoever typed it -- when enough members have signed. That author is the
     * room's id: signing runs at the path the room was derived at, so a
     * translated chunk says which group rendered it simply by being signed.
     *
     * That is the difference from submitting one. A submission says "I am
     * putting this in front of the group" and the group's only recourse
     * afterwards is social. A signature is the group saying it, and it takes a
     * quorum to say. What a chunk means in another tongue is a claim the group
     * is making about somebody else's words, so the second is the honest one.
     *
     * A retranslation goes the same way: the event carries a different text and
     * so a different id, and it replaces the one the group signed before rather
     * than editing it -- there is nothing here to edit, since the old text is
     * already signed.
     */
    fun proposeTranslation(
        localChatRoom: LocalChatRoom,
        originalChunk: MantraChunk,
        translationField: TextFieldState,
        onSuccess: (sessionId: String) -> Unit,
        onFailure: () -> Unit
    ) {
        val text = translationField.text.toString()
        if (text.isBlank()) {
            onFailure.invoke()
            return
        }

        // Guard against double submits from repeated FAB taps.
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val translationChunkEventTemplate = TranslationChunkEvent.build(
                translationChapterId = translationChapterId,
                chunkId = originalChunk.id,
                // The translation sits where the chunk it translates sits. Read
                // off the source rather than counted here: a chapter is
                // translated a chunk at a time and in no particular order, so
                // counting what is translated so far would number them by who
                // got there first.
                index = originalChunk.index,
                text = text,
            )

            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = translationChunkEventTemplate.kind,
                    tags = translationChunkEventTemplate.tags,
                    content = translationChunkEventTemplate.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a translation for signing", error)
            }.getOrNull()

            if (session != null) {
                viewModelScope.launch(Dispatchers.Main) {
                    onSuccess.invoke(session.id)
                }
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    onFailure.invoke()
                }
            }

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "TranslateChunkViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            translationChapterId: String,
            chunkId: String,
            chatRoomId: String,
            relayHint: String?,
            initialTranslateChunkUIState: TranslateChunkUIState = TranslateChunkUIState.Loading,
            mantraRepository: MantraRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TranslateChunkViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    translationChapterId = translationChapterId,
                    chunkId = chunkId,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialTranslateChunkUIState = initialTranslateChunkUIState,
                    mantraRepository = mantraRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                )
            }
        }
    }
}
