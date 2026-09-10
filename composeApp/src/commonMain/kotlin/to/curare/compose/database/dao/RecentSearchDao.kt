package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM RecentSearch WHERE `query` = :query")
    fun observeRecentSearchByQuery(query: String): Flow<to.curare.compose.database.model.RecentSearch?>

    @Query("SELECT * FROM RecentSearch ORDER BY createdAt DESC LIMIT :limit ")
    suspend fun getAllRecentSearches(limit: Int = 21): List<to.curare.compose.database.model.RecentSearch>

    @Delete
    suspend fun deleteRecentSearch(recentSearch: to.curare.compose.database.model.RecentSearch)

    @Upsert
    suspend fun upsert(recentSearch: to.curare.compose.database.model.RecentSearch)
}