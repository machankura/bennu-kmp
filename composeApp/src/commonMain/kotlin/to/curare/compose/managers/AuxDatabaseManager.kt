package to.curare.compose.managers

class AuxDatabaseManager(
    curareGlobal: to.curare.compose.CurareGlobal
) {
    val auxDatabase by lazy {
        to.curare.compose.database.builder.getRoomDatabase(
            to.curare.compose.database.builder.PlatformDatabaseBuilder.getDatabaseBuilder(
                curareGlobal.platformContext
            )
        )
    }

    /**
     * Dynamic data like user feeds and the likes will be in a in-memory db.
     *
     * We may also decide to just use the normal DB and manage what's saved better...
     */
    val inMemoryAuxDatabase by lazy {
        to.curare.compose.database.builder.getRoomDatabase(
            to.curare.compose.database.builder.PlatformDatabaseBuilder.getInMemoryDatabaseBuilder(
                curareGlobal.platformContext
            )
        )
    }
}