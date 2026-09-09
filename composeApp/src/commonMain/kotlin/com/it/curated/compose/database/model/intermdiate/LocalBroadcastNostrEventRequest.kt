package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalBroadcastNostrEventRequest(
    @Embedded val broadcastNostrEventRequest: com.it.curated.compose.database.model.BroadcastNostrEventRequest,

    @Relation(
        parentColumns = ["nostrEventId"],
        entityColumns = ["id"]
    )
    val nostrEvent: com.it.curated.compose.database.model.NostrEvent,
)
