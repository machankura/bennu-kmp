package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import to.curare.compose.database.model.MarmotGroupEvent

@Dao
interface MarmotGroupEventDao {

    @Upsert
    suspend fun upsert(marmotGroupEvent: MarmotGroupEvent)
}