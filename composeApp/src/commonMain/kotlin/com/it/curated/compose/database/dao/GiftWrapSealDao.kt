package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface GiftWrapSealDao {
    @Query("SELECT * FROM GiftWrapSeal")
    suspend fun  getAllGiftWrapSeals(): List<com.it.curated.compose.database.model.GiftWrapSeal>

    @Upsert
    suspend fun upsert(giftWrapSeal: com.it.curated.compose.database.model.GiftWrapSeal)

}