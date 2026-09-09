package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MantraDialect

@Dao
interface MantraDialectDao {
    @Upsert
    suspend fun upsert(mantraDialect: MantraDialect)

    @Query("SELECT * FROM MantraDialect WHERE chatRoomId = :chatRoomId ORDER BY name")
    suspend fun getDialectsByChatRoomId(chatRoomId: String): List<MantraDialect>

    @Query("SELECT * FROM MantraDialect WHERE id = :id")
    suspend fun getDialectById(id: String): MantraDialect?
}