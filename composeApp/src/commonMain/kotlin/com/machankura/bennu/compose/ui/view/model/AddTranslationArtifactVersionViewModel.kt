package com.machankura.bennu.compose.ui.view.model

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
import com.machankura.bennu.compose.database.model.MantraArtifact
import com.machankura.bennu.compose.database.model.MantraArtifactVersion
import com.machankura.bennu.compose.database.model.MantraChapter
import com.machankura.bennu.compose.database.model.MantraDialect
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.FrostSigningManager
import com.machankura.bennu.compose.managers.TranslationScaffold
import com.machankura.bennu.compose.nostr.nip30303.TranslationArtifactVersionEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.ui.view.state.AddTranslationArtifactVersionUIState

class AddTranslationArtifactVersionViewModel(
    val artifactId: String,
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialAddTranslationArtifactVersionUIState: AddTranslationArtifactVersionUIState,
    val mantraRepository: MantraRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var addTranslationArtifactVersionUIState: AddTranslationArtifactVersionUIState by mutableStateOf(initialAddTranslationArtifactVersionUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiateAddTranslation() {
        viewModelScope.launch(Dispatchers.IO) {
            val artifact = mantraRepository.getArtifact(artifactId)
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            // Translations attach to an artifact version; use the latest one.
            val artifactVersion = mantraRepository.getArtifactVersions(artifactId).firstOrNull()

            addTranslationArtifactVersionUIState = if (artifact == null || localChatRoom == null) {
                AddTranslationArtifactVersionUIState.Error("Couldn't find the artifact")
            } else {
                AddTranslationArtifactVersionUIState.Loaded(
                    artifact = artifact,
                    localChatRoom = localChatRoom,
                    dialects = mantraRepository.getDialects(chatRoomId),
                    artifactVersion = artifactVersion,
                    chapters = artifactVersion
                        ?.let { mantraRepository.getChaptersForArtifactVersion(it.id) }
                        .orEmpty(),
                    canSign = frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Asks the group to sign a translation of the artifact, and a translation
     * chapter for every chapter it has, in one session.
     *
     * Nothing is created here. What goes out is a proposal to sign the lot, and
     * the translation and its chapters appear on every member's device at once,
     * authored by this room's own key rather than by whoever picked the dialect,
     * when enough members have signed. That author is the room's id: signing
     * runs at the path the room was derived at, so a translation says which
     * group's artifact it belongs to simply by being signed.
     *
     * That is the difference from submitting one. A submission says "I am
     * putting this in front of the group" and the group's only recourse
     * afterwards is social. A signature is the group saying it, and it takes a
     * quorum to say. A translation is what the group's readers will read the
     * artifact as, so the second is the honest one.
     *
     * The chapters travel with it rather than being scaffolded on each device,
     * for the same reason the chapter's chunks do: a translation chapter that
     * carries the group's signature can be checked by anybody holding it, and a
     * translation with no chapters is one nobody can start translating. What it
     * costs is [MAX_CHAPTERS_PER_TRANSLATION]: the batch is capped, the
     * translation takes one of its places, and an artifact with more chapters
     * than the rest cannot be translated in one session at all.
     */
    fun addTranslation(
        localChatRoom: LocalChatRoom,
        artifact: MantraArtifact,
        artifactVersion: MantraArtifactVersion,
        dialect: MantraDialect,
        chapters: List<MantraChapter>,
        onSuccess: (sessionId: String) -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits from repeated FAB taps.
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val translationEventTemplate = TranslationArtifactVersionEvent.build(
                artifactVersionId = artifactVersion.id,
                dialectId = dialect.id,
                // The translation is named after its dialect, and inherits the
                // source artifact's visibility and license: it is the same work
                // in another tongue, not a new one to license afresh.
                name = dialect.name,
                visibility = artifact.visibility,
                license = artifact.license,
            )

            val session = runCatching {
                frostSigningRepository.proposeSigningBatch(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    lead = translationEventTemplate,
                    // A translation chapter names the translation it belongs to,
                    // and that id is a hash over the group's key at the room's
                    // path -- neither of which this screen knows or should. The
                    // translation comes back built, and the chapters are laid
                    // out against it.
                    dependents = { translation ->
                        TranslationScaffold.chaptersOf(
                            translationArtifactVersionIds = listOf(translation.id),
                            chapters = chapters.take(MAX_CHAPTERS_PER_TRANSLATION)
                                .map(TranslationScaffold.SourceChapter::of),
                            createdAt = translation.createdAt,
                        )
                    }
                )
            }.onFailure { error ->
                logger.e("Failed to propose a translation for signing", error)
            }.getOrNull()

            if (session != null) {
                // Before navigating, not after: the route this screen sits on
                // is popped on success, which clears this view model and takes
                // `viewModelScope` with it.
                scaffoldRemainingChapters(
                    localChatRoom = localChatRoom,
                    chapters = chapters.drop(MAX_CHAPTERS_PER_TRANSLATION),
                    translationSessionId = session.id,
                )

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
     * Asks the group for the chapters that did not fit the translation's own
     * session, in sessions of their own.
     *
     * The alternative was refusing to translate an artifact of more than
     * [MAX_CHAPTERS_PER_TRANSLATION] chapters at all, which is a cap on how
     * long a book may be dressed up as a cap on a batch. A group with a longer
     * one answers more than once instead.
     *
     * The translation is named as the group will author it: the first item of
     * [translationSessionId] is the unsigned translation, and its id is settled
     * at proposal time rather than when the quorum arrives.
     *
     * These carry the same cost as any second session, and the same one the
     * chapter side carries: if the translation itself fails to reach a quorum
     * these are valid signatures over rows naming a translation nobody has.
     * They fail a foreign key on the way in, are logged, and never become rows
     * -- and the catch-up on the translation is what fills the gap afterwards.
     */
    private suspend fun scaffoldRemainingChapters(
        localChatRoom: LocalChatRoom,
        chapters: List<MantraChapter>,
        translationSessionId: String,
    ) {
        if (chapters.isEmpty()) return

        runCatching {
            val translation = frostSigningRepository
                .unsignedEvents(frostSigningRepository.getItems(translationSessionId))
                .firstOrNull() ?: return@runCatching

            chapters.chunked(FrostSigningManager.MAX_BATCH_SIZE).forEach { batch ->
                frostSigningRepository.proposeSigningBatch(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    events = TranslationScaffold.chaptersOf(
                        translationArtifactVersionIds = listOf(translation.id),
                        chapters = batch.map(TranslationScaffold.SourceChapter::of),
                        createdAt = translation.createdAt,
                    )
                )
            }
        }.onFailure { error ->
            logger.e(
                "Proposed the translation but could not ask for the chapters past the first session",
                error
            )
        }
    }

    companion object {
        private const val TAG = "AddTranslationViewModel"

        /**
         * How many chapters ride in the translation's own session.
         *
         * The translation is signed together with a chapter apiece, and a
         * session signs at most [FrostSigningManager.MAX_BATCH_SIZE] events. The
         * translation is one of them, so the chapters get the rest.
         *
         * A cap on the session, not on the work: an artifact with more chapters
         * than this is not refused, it is proposed in more than one session. A
         * limit whoever is looking at the screen can do nothing about is not a
         * limit worth telling them to work around.
         */
        const val MAX_CHAPTERS_PER_TRANSLATION: Int = FrostSigningManager.MAX_BATCH_SIZE - 1

        fun factory(
            activeUserPublicKey: HexKey,
            artifactId: String,
            chatRoomId: String,
            relayHint: String?,
            initialAddTranslationArtifactVersionUIState: AddTranslationArtifactVersionUIState = AddTranslationArtifactVersionUIState.Loading,
            mantraRepository: MantraRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddTranslationArtifactVersionViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    artifactId = artifactId,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialAddTranslationArtifactVersionUIState = initialAddTranslationArtifactVersionUIState,
                    mantraRepository = mantraRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository,
                )
            }
        }
    }
}
