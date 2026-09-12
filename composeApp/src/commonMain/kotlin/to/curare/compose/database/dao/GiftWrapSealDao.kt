package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface GiftWrapSealDao {
    @Query("SELECT * FROM GiftWrapSeal")
    suspend fun  getAllGiftWrapSeals(): List<to.curare.compose.database.model.GiftWrapSeal>

    @Query("SELECT COUNT(*) FROM GiftWrapSeal WHERE giftWrapMessageId = :giftWrapMessageId")
    suspend fun countForMessage(giftWrapMessageId: String): Int

    @Upsert
    suspend fun upsert(giftWrapSeal: to.curare.compose.database.model.GiftWrapSeal)

}