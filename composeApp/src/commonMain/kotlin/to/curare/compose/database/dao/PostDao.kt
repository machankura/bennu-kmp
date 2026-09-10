package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface PostDao {
    @Query("SELECT * FROM Post")
    suspend fun  getAllPosts(): List<to.curare.compose.database.model.intermdiate.LocalPost>

    @Query("SELECT * FROM Post WHERE id = :id")
    suspend fun getPostById(id: String): to.curare.compose.database.model.Post?

    @Insert
    suspend fun insert(post: to.curare.compose.database.model.Post)

    @Upsert
    suspend fun upsert(post: to.curare.compose.database.model.Post)
}