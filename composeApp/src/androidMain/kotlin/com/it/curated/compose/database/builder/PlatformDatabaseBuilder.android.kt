package com.it.curated.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.it.curated.compose.PlatformContext
import com.it.curated.compose.database.CuratedDatabase

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<CuratedDatabase> {
        val appContext = platformContext.applicationContext

        val dbFile = appContext.getDatabasePath("aux.db")
        return Room.databaseBuilder<CuratedDatabase>(
            context = appContext,
            name = dbFile.absolutePath
        )
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<CuratedDatabase> {
        return Room.inMemoryDatabaseBuilder(
            context = platform.applicationContext
        )
    }
}