package com.it.curated.compose.database

import androidx.room3.RoomDatabaseConstructor

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object CuratedDatabaseConstructor: RoomDatabaseConstructor<CuratedDatabase> {

    override fun initialize(): CuratedDatabase
}