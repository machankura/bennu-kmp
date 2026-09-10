package to.curare.compose.database

import androidx.room3.RoomDatabaseConstructor

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object CurareDatabaseConstructor: RoomDatabaseConstructor<CurareDatabase> {

    override fun initialize(): CurareDatabase
}