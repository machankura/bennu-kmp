package com.machankura.bennu.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.machankura.bennu.compose.PlatformContext
import com.machankura.bennu.compose.database.BennuDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<BennuDatabase> {
        val dbFilePath = documentDirectory() + "/aux.db"
        return Room.databaseBuilder<BennuDatabase>(
            name = dbFilePath,
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun documentDirectory(): String {
        val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        return requireNotNull(documentDirectory?.path)
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<BennuDatabase> {
        return Room.inMemoryDatabaseBuilder()
    }

}