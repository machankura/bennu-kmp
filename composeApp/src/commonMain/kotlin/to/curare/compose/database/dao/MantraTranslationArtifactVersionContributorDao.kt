package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import to.curare.compose.database.model.MantraTranslationArtifactVersionContributor

@Dao
interface MantraTranslationArtifactVersionContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationArtifactVersionContributor: MantraTranslationArtifactVersionContributor)
}