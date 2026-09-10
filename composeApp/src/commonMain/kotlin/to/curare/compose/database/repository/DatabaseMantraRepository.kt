package to.curare.compose.database.repository

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.CoroutineScope
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.model.MantraArtifact
import to.curare.compose.database.model.MantraArtifactVersion
import to.curare.compose.database.model.MantraChapter
import to.curare.compose.database.model.MantraChunk
import to.curare.compose.database.model.MantraDialect
import to.curare.compose.database.model.MantraTranslationArtifactVersion
import to.curare.compose.database.model.MantraTranslationChapter
import to.curare.compose.database.model.MantraTranslationChunk
import to.curare.compose.database.model.MarmotInnerEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.repository.MantraRepository

class DatabaseMantraRepository(
    val database: CurareDatabase,
    val scope: CoroutineScope
): MantraRepository {

    private val logger = Logger.withTag(TAG)

    override suspend fun getArtifacts(chatRoomId: String): List<MantraArtifact> =
        database.mantraArtifactDao().getArtifactsByChatRoomId(chatRoomId)

    override suspend fun getArtifact(id: String): MantraArtifact? =
        database.mantraArtifactDao().getArtifactById(id)

    override suspend fun getArtifactVersions(artifactId: String): List<MantraArtifactVersion> =
        database.mantraArtifactVersionDao().getArtifactVersionsByArtifactId(artifactId)

    override suspend fun getArtifactVersion(id: String): MantraArtifactVersion? =
        database.mantraArtifactVersionDao().getArtifactVersionById(id)

    override suspend fun getChaptersForArtifact(artifactId: String): List<MantraChapter> =
        database.mantraChapterDao().getChaptersByArtifactId(artifactId)

    override suspend fun getChaptersForArtifactVersion(artifactVersionId: String): List<MantraChapter> =
        database.mantraChapterDao().getChaptersByArtifactVersionId(artifactVersionId)

    override suspend fun getChapter(id: String): MantraChapter? =
        database.mantraChapterDao().getChapterById(id)

    override suspend fun getChunksForChapter(chapterId: String): List<MantraChunk> =
        database.mantraChunkDao().getChunksByChapterId(chapterId)

    override suspend fun getChunk(id: String): MantraChunk? =
        database.mantraChunkDao().getChunkById(id)

    override suspend fun getTranslationsForArtifact(artifactId: String): List<MantraTranslationArtifactVersion> =
        database.mantraTranslationArtifactVersionDao().getTranslationsByArtifactId(artifactId)

    override suspend fun getTranslation(id: String): MantraTranslationArtifactVersion? =
        database.mantraTranslationArtifactVersionDao().getTranslationById(id)

    override suspend fun getTranslationsForArtifactVersion(artifactVersionId: String): List<MantraTranslationArtifactVersion> =
        database.mantraTranslationArtifactVersionDao().getTranslationsByArtifactVersionId(artifactVersionId)

    override suspend fun getTranslationChapter(id: String): MantraTranslationChapter? =
        database.mantraTranslationChapterDao().getTranslationChapterById(id)

    override suspend fun getTranslationChapters(translationArtifactVersionId: String): List<MantraTranslationChapter> =
        database.mantraTranslationChapterDao().getTranslationChaptersByTranslationArtifactVersionId(translationArtifactVersionId)

    override suspend fun getTranslationChunks(translationChapterId: String): List<MantraTranslationChunk> =
        database.mantraTranslationChunkDao().getTranslationChunksByTranslationChapterId(translationChapterId)

    override suspend fun getDialects(chatRoomId: String): List<MantraDialect> =
        database.mantraDialectDao().getDialectsByChatRoomId(chatRoomId)

    override suspend fun getDialect(id: String): MantraDialect? =
        database.mantraDialectDao().getDialectById(id)

    override suspend fun addArtifactVersion(
        localChatRoom: LocalChatRoom,
        artifactId: HexKey,
        versionLabel: String,
        userPublicKey: HexKey,
    ): MantraArtifactVersion? {
        return database.mantraDao().addArtifactVersion(
            localChatRoom = localChatRoom,
            artifactId = artifactId,
            versionLabel = versionLabel,
            userPublicKey = userPublicKey
        )
    }

    companion object {
        private const val TAG = "DatabaseMantraRepository"
    }
}
