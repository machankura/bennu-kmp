package to.curare.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import to.curare.compose.PlatformContext
import to.curare.compose.database.CurareDatabase

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<CurareDatabase> {
        val appContext = platformContext.applicationContext

        val dbFile = appContext.getDatabasePath("aux.db")
        return Room.databaseBuilder<CurareDatabase>(
            context = appContext,
            name = dbFile.absolutePath
        )
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<CurareDatabase> {
        return Room.inMemoryDatabaseBuilder(
            context = platform.applicationContext
        )
    }
}