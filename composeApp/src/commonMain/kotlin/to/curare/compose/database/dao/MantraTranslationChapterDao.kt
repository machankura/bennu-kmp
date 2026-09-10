package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.Upsert
import to.curare.compose.database.model.MantraTranslationChapter

@Dao
interface MantraTranslationChapterDao {
    @Upsert
    suspend fun upsert(mantraTranslationChapter: MantraTranslationChapter)

    @Query("SELECT * FROM MantraTranslationChapter WHERE translationArtifactVersionId = :translationArtifactVersionId ORDER BY `index` ASC")
    suspend fun getTranslationChaptersByTranslationArtifactVersionId(translationArtifactVersionId: String): List<MantraTranslationChapter>

    @Query("SELECT * FROM MantraTranslationChapter WHERE id = :id")
    suspend fun getTranslationChapterById(id: String): MantraTranslationChapter?
}