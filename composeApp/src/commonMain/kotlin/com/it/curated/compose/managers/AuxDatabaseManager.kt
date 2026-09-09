package com.it.curated.compose.managers

class AuxDatabaseManager(
    curatedGlobal: com.it.curated.compose.CuratedGlobal
) {
    val auxDatabase by lazy {
        com.it.curated.compose.database.builder.getRoomDatabase(
            com.it.curated.compose.database.builder.PlatformDatabaseBuilder.getDatabaseBuilder(
                curatedGlobal.platformContext
            )
        )
    }

    /**
     * Dynamic data like user feeds and the likes will be in a in-memory db.
     *
     * We may also decide to just use the normal DB and manage what's saved better...
     */
    val inMemoryAuxDatabase by lazy {
        com.it.curated.compose.database.builder.getRoomDatabase(
            com.it.curated.compose.database.builder.PlatformDatabaseBuilder.getInMemoryDatabaseBuilder(
                curatedGlobal.platformContext
            )
        )
    }
}