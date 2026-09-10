package to.curare.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalMention(
    @Embedded val mention: to.curare.compose.database.model.Mention,

    @Relation(
        parentColumns = ["mentionedPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: to.curare.compose.database.model.Profile?,
)
