package com.it.curated.compose.database.model.traits

import kotlin.time.Instant

interface LocalStoreEntity {
    val savedAt: Instant
}