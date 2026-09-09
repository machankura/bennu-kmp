package com.it.curated.compose.database.model.traits

import kotlin.time.Instant

interface UserViewableEntity {
    val viewedAt: Instant?
}