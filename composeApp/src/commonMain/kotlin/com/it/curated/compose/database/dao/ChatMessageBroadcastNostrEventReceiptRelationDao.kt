package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface ChatMessageBroadcastNostrEventReceiptRelationDao {
    @Upsert
    suspend fun upsert(chatMessageBroadcastNostrEventReceiptRelation: com.it.curated.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation)
}