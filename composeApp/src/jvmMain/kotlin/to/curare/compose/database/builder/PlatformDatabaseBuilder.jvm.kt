package to.curare.compose.database.builder

import androidx.room3.Room
import androidx.room3.RoomDatabase
import to.curare.compose.PlatformContext
import to.curare.compose.database.CurareDatabase
import java.io.File

actual object PlatformDatabaseBuilder {
    actual fun getDatabaseBuilder(platformContext: PlatformContext): RoomDatabase.Builder<CurareDatabase> {
        // Under the context's application directory, not java.io.tmpdir -- which is what the
        // abandoned Aux implementation did behind a TODO, and which most systems clear on
        // reboot. The file keeps android's name so one app does not have two on disk.
        val dbFile = File(platformContext.applicationDir.apply { mkdirs() }, "aux.db")
        return Room.databaseBuilder<CurareDatabase>(name = dbFile.absolutePath)
    }

    actual fun getInMemoryDatabaseBuilder(platform: PlatformContext): RoomDatabase.Builder<CurareDatabase> {
        return Room.inMemoryDatabaseBuilder()
    }
}
