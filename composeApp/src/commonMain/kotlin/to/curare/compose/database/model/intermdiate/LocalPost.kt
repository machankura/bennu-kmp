package to.curare.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation
import to.curare.compose.database.model.Post
import to.curare.compose.database.model.Profile

data class LocalPost(
    @Embedded val post: Post,
    @Relation(
        parentColumns = ["profilePublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: Profile
)
