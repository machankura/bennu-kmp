package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalMention(
    @Embedded val mention: com.it.curated.compose.database.model.Mention,

    @Relation(
        parentColumns = ["mentionedPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.it.curated.compose.database.model.Profile?,
)
