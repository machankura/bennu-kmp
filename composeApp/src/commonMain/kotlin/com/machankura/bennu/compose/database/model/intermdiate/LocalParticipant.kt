package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalParticipant(
    @Embedded val participant: com.machankura.bennu.compose.database.model.Participant,

    @Relation(
        parentColumns = ["participantPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.bennu.compose.database.model.Profile?,
)
