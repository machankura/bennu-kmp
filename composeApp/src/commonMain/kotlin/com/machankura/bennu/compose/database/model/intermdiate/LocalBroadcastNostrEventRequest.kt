package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalBroadcastNostrEventRequest(
    @Embedded val broadcastNostrEventRequest: com.machankura.bennu.compose.database.model.BroadcastNostrEventRequest,

    @Relation(
        parentColumns = ["nostrEventId"],
        entityColumns = ["id"]
    )
    val nostrEvent: com.machankura.bennu.compose.database.model.NostrEvent,
)
