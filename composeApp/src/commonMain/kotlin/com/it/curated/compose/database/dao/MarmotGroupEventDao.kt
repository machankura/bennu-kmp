package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MarmotGroupEvent

@Dao
interface MarmotGroupEventDao {

    @Upsert
    suspend fun upsert(marmotGroupEvent: MarmotGroupEvent)
}