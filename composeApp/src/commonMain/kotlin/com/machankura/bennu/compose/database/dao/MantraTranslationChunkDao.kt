package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy.Companion.IGNORE
import androidx.room3.Query
import androidx.room3.Upsert
import com.machankura.bennu.compose.database.model.MantraTranslationChunk

@Dao
interface MantraTranslationChunkDao {
    @Upsert
    suspend fun upsert(mantraTranslationChunk: MantraTranslationChunk)

    @Query("SELECT * FROM MantraTranslationChunk WHERE translationChapterId = :translationChapterId ORDER BY `index` ASC")
    suspend fun getTranslationChunksByTranslationChapterId(translationChapterId: String): List<MantraTranslationChunk>

    /**
     * Every translation this chapter holds of one source chunk.
     *
     * There should only ever be one, and this is how that is kept true: a
     * retranslation is a new event with a new id rather than an edit, so the
     * row it replaces has to be found and dropped.
     */
    @Query("SELECT * FROM MantraTranslationChunk WHERE translationChapterId = :translationChapterId AND chunkId = :chunkId")
    suspend fun getTranslationChunksByChunkId(
        translationChapterId: String,
        chunkId: String
    ): List<MantraTranslationChunk>

    @Query("DELETE FROM MantraTranslationChunk WHERE id = :id")
    suspend fun deleteById(id: String)
}