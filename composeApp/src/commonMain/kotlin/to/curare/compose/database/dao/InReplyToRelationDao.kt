package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface InReplyToRelationDao {
    @Query("SELECT * FROM InReplyToRelation WHERE replyingNostrEventId = :replyingNostrEventId")
    suspend fun getRepostByReplyingNostrEventId(replyingNostrEventId: String): to.curare.compose.database.model.InReplyToRelation?

    @Query("SELECT * FROM InReplyToRelation WHERE inReplyToNostrEventId = :inReplyToNostrEventId")
    suspend fun getRepostByInReplyToNostrEventId(inReplyToNostrEventId: String): to.curare.compose.database.model.InReplyToRelation?

    @Insert
    suspend fun insert(inReplyToRelation: to.curare.compose.database.model.InReplyToRelation)

    @Upsert
    suspend fun upsert(inReplyToRelation: to.curare.compose.database.model.InReplyToRelation)
}