package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Upsert
import to.curare.compose.database.model.MantraTranslation

@Dao
interface MantraTranslationDao {
    @Upsert
    suspend fun upsert(mantraTranslation: MantraTranslation)
}