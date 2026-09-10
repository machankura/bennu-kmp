package to.curare.compose.database.model.traits

import kotlin.time.Instant

interface TimestampedEntity {
    val createdAt: Instant
    val updatedAt: Instant
}