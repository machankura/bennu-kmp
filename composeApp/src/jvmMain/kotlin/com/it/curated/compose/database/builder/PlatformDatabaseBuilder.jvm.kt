package com.it.curated.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.it.curated.compose.PlatformContext
import com.it.curated.compose.database.CuratedDatabase
import java.io.File

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<CuratedDatabase> {
        // Under the context's application directory, not java.io.tmpdir -- which is what the
        // abandoned Aux implementation did behind a TODO, and which most systems clear on
        // reboot. The file keeps android's name so one app does not have two on disk.
        val dbFile = File(platformContext.applicationDir.apply { mkdirs() }, "aux.db")
        return Room.databaseBuilder<CuratedDatabase>(name = dbFile.absolutePath)
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<CuratedDatabase> {
        return Room.inMemoryDatabaseBuilder()
    }
}
