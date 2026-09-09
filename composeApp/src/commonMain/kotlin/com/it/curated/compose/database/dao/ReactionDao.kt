package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    @Query("SELECT * FROM Reaction")
    suspend fun  getAllReactions(): List<com.it.curated.compose.database.model.Reaction>

    @Query("SELECT * FROM Reaction WHERE id = :id")
    fun getReactionById(id: String): Flow<com.it.curated.compose.database.model.Reaction?>

    @Upsert
    suspend fun upsert(reaction: com.it.curated.compose.database.model.Reaction)
}