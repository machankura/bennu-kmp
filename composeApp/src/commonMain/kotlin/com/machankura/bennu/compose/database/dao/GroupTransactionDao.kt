package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.bennu.compose.database.model.GroupTransaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class GroupTransactionDao {
    @Query("SELECT * FROM GroupTransaction WHERE frostSigningSessionId = :sessionId")
    abstract suspend fun getBySessionId(sessionId: String): GroupTransaction?

    @Query("SELECT * FROM GroupTransaction WHERE frostSigningSessionId = :sessionId")
    abstract fun observeBySessionId(sessionId: String): Flow<GroupTransaction?>

    @Query("SELECT * FROM GroupTransaction WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    abstract suspend fun getByChatRoomId(chatRoomId: String): List<GroupTransaction>

    @Query("SELECT * FROM GroupTransaction WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    abstract fun observeByChatRoomId(chatRoomId: String): Flow<List<GroupTransaction>>

    /**
     * The first write, at proposal or acceptance. Ignores a second: the PSBT is
     * write-once, and a row for this session already holds the one every item's
     * sighash was computed from.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(transaction: GroupTransaction): Long

    /** Every later write -- signed, broadcast, seen, confirmed -- over the row [insertIfAbsent] made. */
    @Upsert
    abstract suspend fun upsert(transaction: GroupTransaction)
}
