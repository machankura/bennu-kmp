package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface GiftWrapMessageDao {
    @Query("SELECT * FROM GiftWrapMessage")
    suspend fun  getAllGiftWrapMessages(): List<to.curare.compose.database.model.GiftWrapMessage>

    @Upsert
    suspend fun upsert(giftWrapMessage: to.curare.compose.database.model.GiftWrapMessage)

}