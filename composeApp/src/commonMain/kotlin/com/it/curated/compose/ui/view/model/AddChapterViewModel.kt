package com.it.curated.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
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
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.managers.FrostSigningManager
import com.it.curated.compose.managers.TranslationScaffold
import com.it.curated.compose.nostr.nip30303.ChapterEvent
import com.it.curated.compose.nostr.nip30303.ChunkEvent
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.FrostSigningRepository
import com.it.curated.compose.repository.MantraRepository
import com.it.curated.compose.text.Markdown
import com.it.curated.compose.ui.view.state.AddChapterUIState

class AddChapterViewModel(
    val artifactId: String,
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialAddChapterUIState: AddChapterUIState,
    val mantraRepository: MantraRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var addChapterUIState: AddChapterUIState by mutableStateOf(initialAddChapterUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateAddChapter() {
        viewModelScope.launch(Dispatchers.IO) {
            val artifact = mantraRepository.getArtifact(artifactId)
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            addChapterUIState = when {
                artifact == null -> AddChapterUIState.Error("Couldn't find the artifact")
                localChatRoom == null -> AddChapterUIState.Error("Couldn't find the chat room")
                else -> AddChapterUIState.Loaded(
                    localChatRoom = localChatRoom,
                    artifact = artifact,
                    // Chapters attach to an artifact version; use the latest one.
                    artifactVersion = mantraRepository.getArtifactVersions(artifactId).firstOrNull(),
                    canSign = frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Asks the group to sign a chapter, and every chunk it splits into, in one
     * session.
     *
     * Nothing is created here. What goes out is a proposal to sign the lot, and
     * the chapter and its chunks appear on every member's device at once,
     * authored by this room's own key rather than by whoever pasted the text,
     * when enough members have signed. That author is the room's id: signing
     * runs at the path the room was derived at, so a chapter says which group's
     * artifact it belongs to simply by being signed.
     *
     * That is the difference from submitting one. A submission says "I am
     * putting this in front of the group" and the group's only recourse
     * afterwards is social. A signature is the group saying it, and it takes a
     * quorum to say. The text everyone will translate from is the group's, so
     * the second is the honest one.
     *
     * The chunks travel as their own events rather than being split back out of
     * the signed chapter on each device, so that each one carries the group's
     * signature over its own text -- a translation is of a chunk, and a chunk
     * that can be checked on its own is worth more than one that can only be
     * checked by re-deriving it. What it costs is [MAX_CHUNKS_PER_CHAPTER]: the
     * batch is capped, the chapter takes one of its places, and a chapter with
     * more paragraphs than the rest cannot be proposed at all.
     */
    fun addChapter(
        localChatRoom: LocalChatRoom,
        artifactVersion: MantraArtifactVersion,
        nameField: TextFieldState,
        originalTextField: TextFieldState,
        onSuccess: (sessionId: String) -> Unit,
        onFailure: () -> Unit
    ) {
        val name = nameField.text.toString()
        val originalText = originalTextField.text.toString()
        val paragraphs = Markdown.splitParagraphs(originalText)

        // The cap is the group's, not this screen's, and proposing past it
        // throws rather than failing softly. The form says so as it is typed;
        // this is the check that has to hold when it does not.
        if (name.isBlank() || paragraphs.isEmpty() || paragraphs.size > MAX_CHUNKS_PER_CHAPTER) {
            onFailure.invoke()
            return
        }

        // Guard against double submits from repeated FAB taps.
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // Where this chapter sits in the version, read now rather than
            // derived on arrival: the index is signed into the event, and a
            // device applying it cannot recount from a version other members
            // may have added to in a different order. The scaffolding proposed
            // below is placed at the same one, so a translation chapter agrees
            // with the chapter it stands for.
            val chapterIndex = mantraRepository.getChaptersForArtifactVersion(artifactVersion.id).size

            val chapterEventTemplate = ChapterEvent.build(
                artifactVersionId = artifactVersion.id,
                name = name,
                originalText = originalText,
                index = chapterIndex,
                wordCount = Markdown.wordCount(originalText),
                characterCount = Markdown.characterCount(originalText),
            )

            val session = runCatching {
                frostSigningRepository.proposeSigningBatch(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    lead = chapterEventTemplate,
                    // A chunk names the chapter it belongs to, and that id is a
                    // hash over the group's key at the room's path -- neither of
                    // which this screen knows or should. The chapter comes back
                    // built, and the chunks are cut from the text it carries.
                    dependents = ChunkEvent::splitOf
                )
            }.onFailure { error ->
                logger.e("Failed to propose a chapter for signing", error)
            }.getOrNull()

            if (session != null) {
                // Before navigating, not after: the route this screen sits on
                // is popped on success, which clears this view model and takes
                // `viewModelScope` with it. Scaffolding launched afterwards
                // would be cancelled somewhere in the middle.
                scaffoldIntoTranslations(
                    localChatRoom = localChatRoom,
                    artifactVersion = artifactVersion,
                    chapterIndex = chapterIndex,
                    chapterSessionId = session.id,
                )

                nameField.clearText()
                originalTextField.clearText()

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

    /**
     * Asks the group to scaffold this chapter into every translation of the
     * version, in sessions of their own.
     *
     * Its own session rather than more items on the chapter's, because the two
     * would otherwise compete for one batch: a chapter is capped at
     * [MAX_CHUNKS_PER_CHAPTER] paragraphs, and every dialect the group works in
     * would take one of those places away. How long a chapter may be and how
     * many languages it is read in have nothing to do with each other, and a
     * shared cap would make the first move whenever somebody else did the
     * second.
     *
     * Sessions run independently -- every message carries the session it
     * belongs to, and each mints its own nonce material -- so a second one
     * opened here costs the admins another approval and nothing else.
     *
     * **What a separate session gives up.** A batch is all-or-nothing; two
     * sessions are not. If the chapter fails to reach a quorum while this one
     * succeeds, these are valid signatures over rows naming a chapter nobody
     * has: they fail a foreign key on the way in, are logged by
     * `applySignedEvent`, and never become rows. Harmless, and not recoverable
     * either -- a re-proposed chapter is a different id -- so the catch-up on
     * the translation is what fills the gap afterwards.
     *
     * The chapter is named as the group will author it: [chapterSessionId]'s
     * first item is the unsigned chapter, whose id is the hash its signers are
     * putting their shares behind, and it is settled at proposal time rather
     * than when the quorum arrives.
     *
     * Failure here is logged and swallowed. The chapter has already been
     * proposed and is the thing that was asked for; losing it because its
     * scaffolding could not be opened would be the wrong way round.
     */
    private suspend fun scaffoldIntoTranslations(
        localChatRoom: LocalChatRoom,
        artifactVersion: MantraArtifactVersion,
        chapterIndex: Int,
        chapterSessionId: String,
    ) {
        runCatching {
            val translations = mantraRepository
                .getTranslationsForArtifactVersion(artifactVersion.id)
                .ifEmpty { return@runCatching }

            val chapter = frostSigningRepository
                .unsignedEvents(frostSigningRepository.getItems(chapterSessionId))
                .firstOrNull() ?: return@runCatching

            // One session per MAX_BATCH_SIZE translations. A group with more
            // dialects than a batch holds is not a group this should refuse;
            // it is one that answers twice.
            translations.chunked(FrostSigningManager.MAX_BATCH_SIZE).forEach { batch ->
                frostSigningRepository.proposeSigningBatch(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    events = TranslationScaffold.chaptersOf(
                        translationArtifactVersionIds = batch.map { it.id },
                        chapters = listOf(
                            TranslationScaffold.SourceChapter(
                                id = chapter.id,
                                index = chapterIndex,
                            )
                        ),
                        createdAt = chapter.createdAt,
                    )
                )
            }
        }.onFailure { error ->
            logger.e(
                "Proposed the chapter but could not ask for it in this version's translations",
                error
            )
        }
    }

    companion object {
        private const val TAG = "AddChapterViewModel"

        /**
         * The most paragraphs a chapter can be proposed with.
         *
         * A chapter is signed together with a chunk per paragraph, and the
         * session signs at most [FrostSigningManager.MAX_BATCH_SIZE] events. The
         * chapter is one of them, so the paragraphs get the rest. A longer
         * chapter has to be split into two, which is a real limit and belongs in
         * front of whoever is typing rather than in a failure afterwards.
         */
        const val MAX_CHUNKS_PER_CHAPTER: Int = FrostSigningManager.MAX_BATCH_SIZE - 1

        fun factory(
            activeUserPublicKey: HexKey,
            artifactId: String,
            chatRoomId: String,
            relayHint: String?,
            initialAddChapterUIState: AddChapterUIState = AddChapterUIState.Loading,
            mantraRepository: MantraRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddChapterViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    artifactId = artifactId,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialAddChapterUIState = initialAddChapterUIState,
                    mantraRepository = mantraRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                )
            }
        }
    }
}
