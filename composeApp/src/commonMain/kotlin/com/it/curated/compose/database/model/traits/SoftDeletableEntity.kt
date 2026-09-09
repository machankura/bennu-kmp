package com.it.curated.compose.database.model.traits

import kotlin.time.Instant

interface SoftDeletableEntity {
    val deletedAt: Instant?
}