package com.machankura.bennu.compose.database.model.traits

import kotlin.time.Instant

interface SoftDeletableEntity {
    val deletedAt: Instant?
}