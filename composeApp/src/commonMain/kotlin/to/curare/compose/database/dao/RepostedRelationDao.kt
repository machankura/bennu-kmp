package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface RepostedRelationDao {
    @Query("SELECT * FROM RepostedRelation")
    suspend fun  getAllReposts(): List<to.curare.compose.database.model.RepostedRelation>

    @Query("SELECT * FROM RepostedRelation WHERE repostingNostrEventId = :repostingNostrEventId")
    suspend fun getRepostByRepostingId(repostingNostrEventId: String): to.curare.compose.database.model.RepostedRelation?

    @Query("SELECT * FROM RepostedRelation WHERE repostedNostrEventId = :repostedNostrEventId")
    suspend fun getRepostByRepostedId(repostedNostrEventId: String): to.curare.compose.database.model.RepostedRelation?

    @Upsert
    suspend fun upsert(repostedRelation: to.curare.compose.database.model.RepostedRelation)
}