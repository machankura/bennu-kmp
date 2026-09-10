package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface SynchronizeNostrEventResultDao {
    @Upsert
    suspend fun upsert(synchronizeNostrEventResult: to.curare.compose.database.model.SynchronizeNostrEventResult)
}