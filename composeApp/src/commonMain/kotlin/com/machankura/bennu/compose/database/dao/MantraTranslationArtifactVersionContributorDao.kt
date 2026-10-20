package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Upsert
import com.machankura.bennu.compose.database.model.MantraTranslationArtifactVersionContributor

@Dao
interface MantraTranslationArtifactVersionContributorDao {
    @Upsert
    suspend fun upsert(mantraTranslationArtifactVersionContributor: MantraTranslationArtifactVersionContributor)
}