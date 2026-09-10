package to.curare.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import to.curare.compose.PlatformContext
import to.curare.compose.database.CurareDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<CurareDatabase> {
        val dbFilePath = documentDirectory() + "/aux.db"
        return Room.databaseBuilder<CurareDatabase>(
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

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<CurareDatabase> {
        return Room.inMemoryDatabaseBuilder()
    }

}