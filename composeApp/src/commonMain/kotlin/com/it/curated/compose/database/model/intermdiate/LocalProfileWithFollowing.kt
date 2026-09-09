package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Junction
import androidx.room3.Relation

data class LocalProfileWithFollowing(
    @Embedded val nostrEvent: com.it.curated.compose.database.model.NostrEvent,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.it.curated.compose.database.model.Profile,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"],
        associateBy = Junction(
            value = com.it.curated.compose.database.model.Connection::class,
            parentColumns = ["sourcePublicKey"],
            entityColumns = ["destinationPublicKey"]
        )
    )
    val following: List<com.it.curated.compose.database.model.Profile>
)
