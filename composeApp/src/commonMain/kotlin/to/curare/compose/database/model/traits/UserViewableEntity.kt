package to.curare.compose.database.model.traits

import kotlin.time.Instant

interface UserViewableEntity {
    val viewedAt: Instant?
}