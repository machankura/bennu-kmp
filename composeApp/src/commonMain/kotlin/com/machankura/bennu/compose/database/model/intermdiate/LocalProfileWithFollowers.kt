package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Junction
import androidx.room3.Relation

data class LocalProfileWithFollowers(
    @Embedded val nostrEvent: com.machankura.bennu.compose.database.model.NostrEvent,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.bennu.compose.database.model.Profile,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"],
        associateBy = Junction(
            value = com.machankura.bennu.compose.database.model.Connection::class,
            parentColumns = ["destinationPublicKey"],
            entityColumns = ["sourcePublicKey"]
        )
    )
    val followers: List<com.machankura.bennu.compose.database.model.Profile>
)
