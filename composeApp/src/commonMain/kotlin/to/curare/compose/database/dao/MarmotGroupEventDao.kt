package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.MarmotGroupEvent

@Dao
interface MarmotGroupEventDao {

    @Upsert
    suspend fun upsert(marmotGroupEvent: MarmotGroupEvent)

    @Query("SELECT * FROM MarmotGroupEvent WHERE id = :id")
    suspend fun getMarmotGroupEventById(id: HexKey): MarmotGroupEvent?
}
