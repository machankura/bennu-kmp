package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalAccount(
    @Embedded val unsignedNostrEvent: com.it.curated.compose.database.model.UnsignedNostrEvent?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val nostrEvent: com.it.curated.compose.database.model.NostrEvent?,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.it.curated.compose.database.model.Profile?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val broadcastNostrEventRequest: com.it.curated.compose.database.model.BroadcastNostrEventRequest?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val broadcastNostrEventReceipt: com.it.curated.compose.database.model.BroadcastNostrEventReceipt?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val synchronizeNostrEventRequests: List<com.it.curated.compose.database.model.SynchronizeNostrEventRequest>,

    )
