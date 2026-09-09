package com.it.curated.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.MantraChapter
import com.it.curated.compose.database.model.MantraChunk
import com.it.curated.compose.database.model.MantraDialect
import com.it.curated.compose.database.model.MantraTranslationArtifactVersion
import com.it.curated.compose.database.model.MantraTranslationChapter
import com.it.curated.compose.database.model.MantraTranslationChunk
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom

interface MantraRepository {
    suspend fun getArtifacts(chatRoomId: String): List<MantraArtifact>

    suspend fun getArtifact(id: String): MantraArtifact?

    suspend fun getArtifactVersions(artifactId: String): List<MantraArtifactVersion>

    suspend fun getArtifactVersion(id: String): MantraArtifactVersion?

    suspend fun getChaptersForArtifact(artifactId: String): List<MantraChapter>

    /**
     * The chapters of one version of an artifact, in reading order. A chapter
     * hangs off a version rather than off the artifact, so this is what a new
     * chapter's index counts.
     */
    suspend fun getChaptersForArtifactVersion(artifactVersionId: String): List<MantraChapter>

    suspend fun getChapter(id: String): MantraChapter?

    suspend fun getChunksForChapter(chapterId: String): List<MantraChunk>

    suspend fun getChunk(id: String): MantraChunk?

    suspend fun getTranslationsForArtifact(artifactId: String): List<MantraTranslationArtifactVersion>

    suspend fun getTranslation(id: String): MantraTranslationArtifactVersion?

    /**
     * The translations of one artifact version: what a chapter signed into that
     * version has to be scaffolded into, and what a translation is caught up
     * against.
     */
    suspend fun getTranslationsForArtifactVersion(artifactVersionId: String): List<MantraTranslationArtifactVersion>

    suspend fun getTranslationChapter(id: String): MantraTranslationChapter?

    suspend fun getTranslationChapters(translationArtifactVersionId: String): List<MantraTranslationChapter>

    suspend fun getTranslationChunks(translationChapterId: String): List<MantraTranslationChunk>

    suspend fun getDialects(chatRoomId: String): List<MantraDialect>

    suspend fun getDialect(id: String): MantraDialect?

    suspend fun addArtifactVersion(
        localChatRoom: LocalChatRoom,
        artifactId: HexKey,
        versionLabel: String,
        userPublicKey: HexKey,
    ): MantraArtifactVersion?

    companion object {
        const val DEFAULT_VISIBILITY = "private"
        const val DEFAULT_LICENSE = "cc"

        val NO_OP_MANTRA_REPOSITORY = object: MantraRepository{
            override suspend fun getArtifacts(chatRoomId: String): List<MantraArtifact> = emptyList()

            override suspend fun getArtifact(id: String): MantraArtifact? = null

            override suspend fun getArtifactVersions(artifactId: String): List<MantraArtifactVersion> = emptyList()

            override suspend fun getArtifactVersion(id: String): MantraArtifactVersion? = null

            override suspend fun getChaptersForArtifact(artifactId: String): List<MantraChapter> = emptyList()

            override suspend fun getChaptersForArtifactVersion(artifactVersionId: String): List<MantraChapter> = emptyList()

            override suspend fun getChapter(id: String): MantraChapter? = null

            override suspend fun getChunksForChapter(chapterId: String): List<MantraChunk> = emptyList()

            override suspend fun getChunk(id: String): MantraChunk? = null

            override suspend fun getTranslationsForArtifact(artifactId: String): List<MantraTranslationArtifactVersion> = emptyList()

            override suspend fun getTranslation(id: String): MantraTranslationArtifactVersion? = null

            override suspend fun getTranslationsForArtifactVersion(artifactVersionId: String): List<MantraTranslationArtifactVersion> = emptyList()

            override suspend fun getTranslationChapter(id: String): MantraTranslationChapter? = null

            override suspend fun getTranslationChapters(translationArtifactVersionId: String): List<MantraTranslationChapter> = emptyList()

            override suspend fun getTranslationChunks(translationChapterId: String): List<MantraTranslationChunk> = emptyList()

            override suspend fun getDialects(chatRoomId: String): List<MantraDialect> = emptyList()

            override suspend fun getDialect(id: String): MantraDialect? = null

            override suspend fun addArtifactVersion(
                localChatRoom: LocalChatRoom,
                artifactId: HexKey,
                versionLabel: String,
                userPublicKey: HexKey,
            ): MantraArtifactVersion? = null
        }
    }
}
