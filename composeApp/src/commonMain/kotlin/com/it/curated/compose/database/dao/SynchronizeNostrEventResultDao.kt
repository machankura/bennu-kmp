package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface SynchronizeNostrEventResultDao {
    @Upsert
    suspend fun upsert(synchronizeNostrEventResult: com.it.curated.compose.database.model.SynchronizeNostrEventResult)
}