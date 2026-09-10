package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface NegentropySynchronizeResultDao {
    @Upsert
    suspend fun upsert(negentropySynchronizeResult: to.curare.compose.database.model.NegentropySynchronizeResult)
}