package com.machankura.bennu.compose.database.builder

import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.machankura.bennu.compose.database.migrations.MIGRATION_3_4
import com.machankura.bennu.compose.database.migrations.MIGRATION_9_10
import com.machankura.bennu.compose.database.migrations.MIGRATION_13_14
import com.machankura.bennu.compose.database.migrations.MIGRATION_14_15
import com.machankura.bennu.compose.database.migrations.MIGRATION_26_27
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

expect object PlatformDatabaseBuilder {
    fun getDatabaseBuilder(platformContext: com.machankura.bennu.compose.PlatformContext): RoomDatabase.Builder<com.machankura.bennu.compose.database.BennuDatabase>

    fun getInMemoryDatabaseBuilder(platform: com.machankura.bennu.compose.PlatformContext): RoomDatabase.Builder<com.machankura.bennu.compose.database.BennuDatabase>
}

fun getRoomDatabase(
    builder: RoomDatabase.Builder<com.machankura.bennu.compose.database.BennuDatabase>
): com.machankura.bennu.compose.database.BennuDatabase {
    return builder
        // Everything else Room generates itself. These five move data rather than
        // only changing shape, which an AutoMigration cannot express: 3->4 rewrites
        // chat rows, 9->10 copies a session's per-event columns onto the items
        // table before dropping them, 13->14 renames a column and rewrites the
        // chat rows that named it the old way, 14->15 adds a column and deletes
        // the chat rows written for messages from before this device joined, and
        // 26->27 drops the document tables and deletes the chat rows nothing
        // renders any more.
        .addMigrations(MIGRATION_3_4, MIGRATION_9_10, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_26_27)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}