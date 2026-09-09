package com.it.curated.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.it.curated.compose.database.model.MarmotKeyPackage
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.flow.Flow

@Dao
interface MarmotKeyPackageDao {
    @Query("SELECT * FROM MarmotKeyPackage WHERE publicKey = :publicKey ORDER BY createdAt DESC")
    suspend fun getMarmotKeyPackageForPublicKey(publicKey: HexKey): MarmotKeyPackage?

    /**
     * Observable counterpart. The chat gates used to read the key package once, before the
     * relay round-trip that fetches it could possibly have finished, and latch a terminal
     * error — so a peer whose key package arrived a second later stayed unreachable until
     * the screen was rebuilt.
     */
    @Query("SELECT * FROM MarmotKeyPackage WHERE publicKey = :publicKey ORDER BY createdAt DESC LIMIT 1")
    fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey): Flow<MarmotKeyPackage?>

    @Query("SELECT * FROM MarmotKeyPackage WHERE id = :id ORDER BY createdAt DESC")
    suspend fun getMarmotKeyPackageById(id: HexKey): MarmotKeyPackage?

    @Upsert
    suspend fun upsert(marmotKeyPackage: MarmotKeyPackage)
}