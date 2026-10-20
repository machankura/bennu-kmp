package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.MantraArtifact
import com.machankura.bennu.compose.database.model.MantraArtifactVersion
import com.machankura.bennu.compose.database.model.MantraChapter
import com.machankura.bennu.compose.database.model.MantraDialect
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom

sealed interface AddTranslationArtifactVersionUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val artifact: MantraArtifact,

        /**
         * The dialects this room has signed into existence. A translation is
         * into one of them and only one of them: an invented dialect is a
         * dialect nobody agreed to, and the room already has a screen for
         * asking the group for a new one.
         */
        val dialects: List<MantraDialect> = emptyList(),

        /**
         * The version being translated: the artifact's latest.
         *
         * Null on an artifact that has none, which is one there is nothing to
         * translate -- a translation is of a version rather than of an artifact.
         */
        val artifactVersion: MantraArtifactVersion? = null,

        /**
         * The chapters of that version, which the translation is laid out
         * against: a translation chapter each, signed in the same session.
         *
         * Held here rather than re-read when proposing, so that the count the
         * form shows is the count the group is asked to sign.
         */
        val chapters: List<MantraChapter> = emptyList(),

        /**
         * Whether the group holds a shared key. A translation is signed into
         * existence now rather than submitted, so a group without one cannot
         * start one here at all.
         */
        val canSign: Boolean = false,
    ): AddTranslationArtifactVersionUIState

    data class Error(
        val message: String
    ): AddTranslationArtifactVersionUIState

    data object Loading: AddTranslationArtifactVersionUIState
}
