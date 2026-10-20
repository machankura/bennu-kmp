package com.machankura.bennu.compose.database

import androidx.room3.RoomDatabaseConstructor

@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object BennuDatabaseConstructor: RoomDatabaseConstructor<BennuDatabase> {

    override fun initialize(): BennuDatabase
}