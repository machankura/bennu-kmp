package to.curare.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalParticipant(
    @Embedded val participant: to.curare.compose.database.model.Participant,

    @Relation(
        parentColumns = ["participantPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: to.curare.compose.database.model.Profile?,
)
