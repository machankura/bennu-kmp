package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface NegentropySynchronizeResultDao {
    @Upsert
    suspend fun upsert(negentropySynchronizeResult: com.machankura.bennu.compose.database.model.NegentropySynchronizeResult)
}