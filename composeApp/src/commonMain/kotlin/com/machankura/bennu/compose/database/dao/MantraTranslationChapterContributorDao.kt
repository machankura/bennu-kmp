package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import com.machankura.bennu.compose.database.model.MantraTranslationChapter
import com.machankura.bennu.compose.database.model.MantraTranslationChapterContributor

@Dao
interface MantraTranslationChapterContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationChapterContributor: MantraTranslationChapterContributor)
}