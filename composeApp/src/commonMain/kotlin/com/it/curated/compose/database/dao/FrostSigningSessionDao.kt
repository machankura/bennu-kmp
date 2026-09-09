package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.flow.Flow
import com.it.curated.compose.database.model.FrostSignerMessage
import com.it.curated.compose.database.model.FrostSigningItem
import com.it.curated.compose.database.model.FrostSigningSession
import com.it.curated.compose.database.model.intermdiate.LocalFrostSigningSession

@Dao
interface FrostSigningSessionDao {
    @Query("SELECT * FROM FrostSigningSession WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): FrostSigningSession?

    @Query("SELECT * FROM FrostSigningSession WHERE id = :sessionId")
    fun observeSessionById(sessionId: String): Flow<FrostSigningSession?>

    /**
     * A room's signing sessions, newest first, each with the events it signs and
     * the member who proposed it.
     *
     * Unlike a DKG a group signs repeatedly, so there is no single "current" one
     * to observe -- and no single one to show, which is what the proposal list
     * exists for. See [LocalFrostSigningSession] for why the items travel with
     * the session rather than being fetched per row.
     */
    @Transaction
    @Query("SELECT * FROM FrostSigningSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>>

    @Query("SELECT * FROM FrostSigningSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestSessionForChatRoom(chatRoomId: String): FrostSigningSession?

    @Query("SELECT * FROM FrostSigningSession WHERE chatRoomId = :chatRoomId ORDER BY createdAt DESC")
    suspend fun getSessionsForChatRoom(chatRoomId: String): List<FrostSigningSession>

    @Upsert
    suspend fun upsert(frostSigningSession: FrostSigningSession)

    @Upsert
    suspend fun upsert(frostSignerMessage: FrostSignerMessage)

    /**
     * A session's events, in the order the batch fixed at proposal.
     *
     * Every join of nonces and partial signatures is positional, so this
     * ordering is part of the protocol rather than a presentation choice —
     * two devices reading a batch in different orders aggregate against
     * different messages.
     */
    @Query("SELECT * FROM FrostSigningItem WHERE sessionId = :sessionId ORDER BY itemIndex ASC")
    suspend fun getItems(sessionId: String): List<FrostSigningItem>

    @Query("SELECT * FROM FrostSigningItem WHERE sessionId = :sessionId ORDER BY itemIndex ASC")
    fun observeItems(sessionId: String): Flow<List<FrostSigningItem>>

    @Query("SELECT * FROM FrostSigningItem WHERE sessionId = :sessionId AND itemIndex = :itemIndex")
    suspend fun getItem(sessionId: String, itemIndex: Int): FrostSigningItem?

    @Upsert
    suspend fun upsert(frostSigningItem: FrostSigningItem)

    /**
     * Writes a whole batch's worth of items at once.
     *
     * A transaction rather than a loop because the coordinator's two broadcasts
     * each settle every item together: a half-written aggregate would leave the
     * session gated on `signerIds` while some items had no nonce to aggregate
     * against, which is a stall no message can clear.
     */
    @Transaction
    @Upsert
    suspend fun upsertItems(items: List<FrostSigningItem>)

    /** How many events this session signs. Derived rather than stored — see [FrostSigningItem]. */
    @Query("SELECT COUNT(*) FROM FrostSigningItem WHERE sessionId = :sessionId")
    suspend fun countItems(sessionId: String): Int

    /** How many of them the group has finished. Equal to [countItems] once it is done. */
    @Query("SELECT COUNT(*) FROM FrostSigningItem WHERE sessionId = :sessionId AND signature IS NOT NULL")
    suspend fun countSignedItems(sessionId: String): Int

    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId AND kind = :kind ORDER BY createdAt ASC")
    suspend fun getMessagesByKind(sessionId: String, kind: Kind): List<FrostSignerMessage>

    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>>

    @Query("SELECT COUNT(*) FROM FrostSignerMessage WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun countMessagesByKind(sessionId: String, kind: Kind): Int

    @Query("SELECT * FROM FrostSignerMessage WHERE sessionId = :sessionId AND kind = :kind AND signerPublicKey = :signerPublicKey")
    suspend fun getMessage(sessionId: String, kind: Kind, signerPublicKey: HexKey): FrostSignerMessage?
}
