package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MantraTranslationArtifactVersion

@Dao
interface MantraTranslationArtifactVersionDao {
    @Upsert
    suspend fun upsert(mantraTranslationArtifactVersion: MantraTranslationArtifactVersion)

    /** Translations targeting any version of an artifact. */
    @Query(
        """
        SELECT MantraTranslationArtifactVersion.* FROM MantraTranslationArtifactVersion
        INNER JOIN MantraArtifactVersion
            ON MantraTranslationArtifactVersion.artifactVersionId = MantraArtifactVersion.id
        WHERE MantraArtifactVersion.artifactId = :artifactId
        ORDER BY MantraTranslationArtifactVersion.createdAt DESC
        """
    )
    suspend fun getTranslationsByArtifactId(artifactId: String): List<MantraTranslationArtifactVersion>

    /**
     * Translations of one version, which is what a chapter of that version has
     * to be scaffolded into. Narrower than [getTranslationsByArtifactId]: a
     * chapter belongs to a version, and a translation of an older version is
     * not a translation the chapter is in.
     */
    @Query(
        """
        SELECT * FROM MantraTranslationArtifactVersion
        WHERE artifactVersionId = :artifactVersionId
        ORDER BY createdAt DESC
        """
    )
    suspend fun getTranslationsByArtifactVersionId(artifactVersionId: String): List<MantraTranslationArtifactVersion>

    @Query("SELECT * FROM MantraTranslationArtifactVersion WHERE id = :id")
    suspend fun getTranslationById(id: String): MantraTranslationArtifactVersion?
}