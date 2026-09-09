package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SynchronizeNostrEventRequestDao {
    // See NegentropySynchronizeRequestDao: the old `createdAt > :createdAt` bound a
    // once-evaluated `Clock.System.now()` for the life of the Flow and hid every
    // request queued before the observer subscribed.
    @Query("SELECT * FROM SynchronizeNostrEventRequest WHERE status = :status ORDER BY createdAt ASC, id ASC LIMIT 1")
    fun observeSynchronizeNostrEventRequestsByStatus(status: String): Flow<com.it.curated.compose.database.model.SynchronizeNostrEventRequest?>

    @Query("SELECT COUNT(*) FROM SynchronizeNostrEventRequest WHERE purpose = :purpose AND status IN (:status)")
    fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount(purpose: String, status: List<String>): Flow<Int>

    @Upsert
    suspend fun upsert(synchronizeNostrEventRequest: com.it.curated.compose.database.model.SynchronizeNostrEventRequest)

    @Insert
    suspend fun insert(synchronizeNostrEventRequests: List<com.it.curated.compose.database.model.SynchronizeNostrEventRequest>)
}