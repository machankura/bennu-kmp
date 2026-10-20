package com.machankura.bennu.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.database.model.MarmotGroupEvent

@Dao
interface MarmotGroupEventDao {

    @Upsert
    suspend fun upsert(marmotGroupEvent: MarmotGroupEvent)

    @Query("SELECT * FROM MarmotGroupEvent WHERE id = :id")
    suspend fun getMarmotGroupEventById(id: HexKey): MarmotGroupEvent?
}
