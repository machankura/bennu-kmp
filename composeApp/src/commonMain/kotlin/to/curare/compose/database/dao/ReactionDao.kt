package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    @Query("SELECT * FROM Reaction")
    suspend fun  getAllReactions(): List<to.curare.compose.database.model.Reaction>

    @Query("SELECT * FROM Reaction WHERE id = :id")
    fun getReactionById(id: String): Flow<to.curare.compose.database.model.Reaction?>

    @Upsert
    suspend fun upsert(reaction: to.curare.compose.database.model.Reaction)
}