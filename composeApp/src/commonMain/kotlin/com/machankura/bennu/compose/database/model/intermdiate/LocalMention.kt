package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalMention(
    @Embedded val mention: com.machankura.bennu.compose.database.model.Mention,

    @Relation(
        parentColumns = ["mentionedPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.bennu.compose.database.model.Profile?,
)
