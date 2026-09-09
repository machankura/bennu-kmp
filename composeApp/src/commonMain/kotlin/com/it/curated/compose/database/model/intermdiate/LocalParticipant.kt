package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalParticipant(
    @Embedded val participant: com.it.curated.compose.database.model.Participant,

    @Relation(
        parentColumns = ["participantPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.it.curated.compose.database.model.Profile?,
)
