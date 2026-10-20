package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.bennu.compose.database.model.MantraChapter

@Dao
interface MantraChapterDao {
    @Upsert
    suspend fun upsert(mantraChapter: MantraChapter)

    /** Chapters across all versions of an artifact, ordered by their in-book index. */
    @Query(
        """
        SELECT MantraChapter.* FROM MantraChapter
        INNER JOIN MantraArtifactVersion
            ON MantraChapter.artifactVersionId = MantraArtifactVersion.id
        WHERE MantraArtifactVersion.artifactId = :artifactId
        ORDER BY MantraChapter.`index` ASC
        """
    )
    suspend fun getChaptersByArtifactId(artifactId: String): List<MantraChapter>

    @Query("SELECT * FROM MantraChapter WHERE artifactVersionId = :artifactVersionId ORDER BY `index` ASC")
    suspend fun getChaptersByArtifactVersionId(artifactVersionId: String): List<MantraChapter>

    @Query("SELECT * FROM MantraChapter WHERE id = :id")
    suspend fun getChapterById(id: String): MantraChapter?
}