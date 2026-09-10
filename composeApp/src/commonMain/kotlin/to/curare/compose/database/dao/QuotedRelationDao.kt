package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface QuotedRelationDao {
    @Query("SELECT * FROM QuotedRelation WHERE quotingNostrEventId = :quotingNostrEventId")
    suspend fun getRepostByQuotingNostrEventId(quotingNostrEventId: String): to.curare.compose.database.model.QuotedRelation?

    @Query("SELECT * FROM QuotedRelation WHERE quotedNostrEventId = :quotedNostrEventId")
    suspend fun getRepostByQuotedNostrEventId(quotedNostrEventId: String): to.curare.compose.database.model.QuotedRelation?

    @Insert
    suspend fun insert(quotedRelation: to.curare.compose.database.model.QuotedRelation)

    @Upsert
    suspend fun upsert(quotedRelation: to.curare.compose.database.model.QuotedRelation)
}