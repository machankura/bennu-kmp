package com.machankura.bennu.compose.managers

class AuxDatabaseManager(
    bennuGlobal: com.machankura.bennu.compose.BennuGlobal
) {
    val auxDatabase by lazy {
        com.machankura.bennu.compose.database.builder.getRoomDatabase(
            com.machankura.bennu.compose.database.builder.PlatformDatabaseBuilder.getDatabaseBuilder(
                bennuGlobal.platformContext
            )
        )
    }

    /**
     * Dynamic data like user feeds and the likes will be in a in-memory db.
     *
     * We may also decide to just use the normal DB and manage what's saved better...
     */
    val inMemoryAuxDatabase by lazy {
        com.machankura.bennu.compose.database.builder.getRoomDatabase(
            com.machankura.bennu.compose.database.builder.PlatformDatabaseBuilder.getInMemoryDatabaseBuilder(
                bennuGlobal.platformContext
            )
        )
    }
}