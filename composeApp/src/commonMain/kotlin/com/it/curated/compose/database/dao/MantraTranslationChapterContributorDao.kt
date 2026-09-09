package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MantraTranslationChapter
import com.it.curated.compose.database.model.MantraTranslationChapterContributor

@Dao
interface MantraTranslationChapterContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationChapterContributor: MantraTranslationChapterContributor)
}