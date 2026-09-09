package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface ChatMessageNostrEventRelationDao {

    @Upsert
    suspend fun upsert(chatMessageNostrEventRelation: com.it.curated.compose.database.model.ChatMessageNostrEventRelation)
}