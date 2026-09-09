package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert

@Dao
interface NostrEventRelayDao {

    @Upsert
    suspend fun upsert(nostrEventRelay: com.it.curated.compose.database.model.NostrEventRelay)

}