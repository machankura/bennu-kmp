package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface NegentropySynchronizeResultDao {
    @Upsert
    suspend fun upsert(negentropySynchronizeResult: com.it.curated.compose.database.model.NegentropySynchronizeResult)
}