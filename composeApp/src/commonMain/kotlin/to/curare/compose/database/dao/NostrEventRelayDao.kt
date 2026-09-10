package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface NostrEventRelayDao {

    @Upsert
    suspend fun upsert(nostrEventRelay: to.curare.compose.database.model.NostrEventRelay)

}