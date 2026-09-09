package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface GiftWrapMessageDao {
    @Query("SELECT * FROM GiftWrapMessage")
    suspend fun  getAllGiftWrapMessages(): List<com.it.curated.compose.database.model.GiftWrapMessage>

    @Upsert
    suspend fun upsert(giftWrapMessage: com.it.curated.compose.database.model.GiftWrapMessage)

}