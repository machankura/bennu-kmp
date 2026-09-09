package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MantraArtifact

@Dao
interface MantraArtifactDao {
    @Upsert
    suspend fun upsert(mantraArtifact: MantraArtifact)

    @Query("SELECT * FROM MantraArtifact WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    suspend fun getArtifactsByChatRoomId(chatRoomId: String): List<MantraArtifact>

    @Query("SELECT * FROM MantraArtifact WHERE id = :id")
    suspend fun getArtifactById(id: String): MantraArtifact?
}