package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface ChatMessageNostrEventRelationDao {

    @Upsert
    suspend fun upsert(chatMessageNostrEventRelation: com.machankura.bennu.compose.database.model.ChatMessageNostrEventRelation)
}