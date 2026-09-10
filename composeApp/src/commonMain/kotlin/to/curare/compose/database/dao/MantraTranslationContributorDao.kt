package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import to.curare.compose.database.model.MantraTranslationContributor

@Dao
interface MantraTranslationContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationContributor: MantraTranslationContributor)
}