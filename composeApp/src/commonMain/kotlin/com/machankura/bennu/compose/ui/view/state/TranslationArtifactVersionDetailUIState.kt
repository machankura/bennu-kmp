package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.MantraChapter
import com.machankura.bennu.compose.database.model.MantraTranslationArtifactVersion
import com.machankura.bennu.compose.database.model.MantraTranslationChapter
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom

/** A translation chapter with how many of its chunks have been translated. */
data class TranslationChapterProgress(
    val chapter: MantraTranslationChapter,
    val totalChunks: Int,
    val translatedChunks: Int,
)

sealed interface TranslationArtifactVersionDetailUIState {
    data class Loaded(
        val translation: MantraTranslationArtifactVersion,
        val chapters: List<TranslationChapterProgress> = emptyList(),

        /**
         * The room this translation belongs to, which is who would sign the
         * chapters it is missing. Null when it cannot be read, which is a
         * translation nothing can be proposed for.
         */
        val localChatRoom: LocalChatRoom? = null,

        /**
         * Chapters of the source version this translation has nothing for.
         *
         * A translation covers the chapters that existed when it was proposed,
         * and a chapter signed afterwards is scaffolded into it by a session of
         * that chapter's own -- which the two can miss, by being proposed at the
         * same moment or by one of them not reaching a quorum. This is what is
         * left over when they do, and it is shown rather than inferred because
         * a chapter nobody can translate is invisible from the translation.
         */
        val missingChapters: List<MantraChapter> = emptyList(),

        /** Whether the group holds a shared key, without which nothing can be proposed. */
        val canSign: Boolean = false,
    ): TranslationArtifactVersionDetailUIState

    data class Error(
        val message: String
    ): TranslationArtifactVersionDetailUIState

    data object Loading: TranslationArtifactVersionDetailUIState
}
