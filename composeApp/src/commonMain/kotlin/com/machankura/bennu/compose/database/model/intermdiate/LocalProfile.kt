package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.Profile

data class LocalProfile(
    @Embedded val nostrEvent: NostrEvent,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: Profile,
)
