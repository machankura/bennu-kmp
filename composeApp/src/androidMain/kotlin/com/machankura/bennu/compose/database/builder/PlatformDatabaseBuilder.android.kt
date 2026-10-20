package com.machankura.bennu.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.machankura.bennu.compose.PlatformContext
import com.machankura.bennu.compose.database.BennuDatabase

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<BennuDatabase> {
        val appContext = platformContext.applicationContext

        val dbFile = appContext.getDatabasePath("aux.db")
        return Room.databaseBuilder<BennuDatabase>(
            context = appContext,
            name = dbFile.absolutePath
        )
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<BennuDatabase> {
        return Room.inMemoryDatabaseBuilder(
            context = platform.applicationContext
        )
    }
}