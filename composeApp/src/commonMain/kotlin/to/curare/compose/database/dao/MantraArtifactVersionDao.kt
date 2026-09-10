package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.Upsert
import to.curare.compose.database.model.MantraArtifactVersion

@Dao
interface MantraArtifactVersionDao {
    @Upsert
    suspend fun upsert(mantraArtifactVersion: MantraArtifactVersion)

    @Query("SELECT * FROM MantraArtifactVersion WHERE artifactId = :artifactId ORDER BY createdAt DESC")
    suspend fun getArtifactVersionsByArtifactId(artifactId: String): List<MantraArtifactVersion>

    @Query("SELECT * FROM MantraArtifactVersion WHERE id = :id")
    suspend fun getArtifactVersionById(id: String): MantraArtifactVersion?
}