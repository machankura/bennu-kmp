package com.machankura.bennu.compose.database.model.traits

import kotlin.time.Instant

interface LocalStoreEntity {
    val savedAt: Instant
}