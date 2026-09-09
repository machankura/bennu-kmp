package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MantraTranslationContributor

@Dao
interface MantraTranslationContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationContributor: MantraTranslationContributor)
}