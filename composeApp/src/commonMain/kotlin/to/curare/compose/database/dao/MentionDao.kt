package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Upsert

@Dao
interface MentionDao {

    @Insert(
        onConflict = IGNORE
    )
    suspend fun insert(mention: to.curare.compose.database.model.Mention)

    @Upsert
    suspend fun upsert(mention: to.curare.compose.database.model.Mention)
}