package to.curare.compose.ui.view.state

import to.curare.compose.database.model.MantraArtifact
import to.curare.compose.database.model.MantraArtifactVersion
import to.curare.compose.database.model.MantraChapter
import to.curare.compose.database.model.MantraTranslationArtifactVersion

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
