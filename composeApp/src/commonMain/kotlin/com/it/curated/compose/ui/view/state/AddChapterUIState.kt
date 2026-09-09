package com.it.curated.compose.ui.view.state

import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom

sealed interface AddChapterUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val artifact: MantraArtifact,

        /**
         * The version the chapter would attach to: the artifact's latest.
         *
         * Null on an artifact that has none, which is one nothing can be added
         * to -- a chapter hangs off a version rather than off an artifact.
         */
        val artifactVersion: MantraArtifactVersion? = null,

        /**
         * Whether the group holds a shared key. A chapter is signed into
         * existence now rather than submitted, so a group without one cannot
         * add one here at all.
         */
        val canSign: Boolean = false,
    ): AddChapterUIState

    data class Error(
        val message: String
    ): AddChapterUIState

    data object Loading: AddChapterUIState
}
