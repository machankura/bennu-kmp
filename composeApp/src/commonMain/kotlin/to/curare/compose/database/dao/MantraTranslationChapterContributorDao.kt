package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import to.curare.compose.database.model.MantraTranslationChapter
import to.curare.compose.database.model.MantraTranslationChapterContributor

@Dao
interface MantraTranslationChapterContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationChapterContributor: MantraTranslationChapterContributor)
}