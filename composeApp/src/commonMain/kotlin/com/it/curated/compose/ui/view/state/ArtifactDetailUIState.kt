package com.it.curated.compose.ui.view.state

import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.MantraChapter
import com.it.curated.compose.database.model.MantraTranslationArtifactVersion

sealed interface ArtifactDetailUIState {
    data class Loaded(
        val artifact: MantraArtifact,
        val versions: List<MantraArtifactVersion> = emptyList(),
        val chapters: List<MantraChapter> = emptyList(),
        val translations: List<MantraTranslationArtifactVersion> = emptyList(),
    ): ArtifactDetailUIState

    data class Error(
        val message: String
    ): ArtifactDetailUIState

    data object Loading: ArtifactDetailUIState
}
