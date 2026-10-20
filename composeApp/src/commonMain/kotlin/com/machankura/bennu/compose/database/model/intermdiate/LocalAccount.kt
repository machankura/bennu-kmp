package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalAccount(
    @Embedded val unsignedNostrEvent: com.machankura.bennu.compose.database.model.UnsignedNostrEvent?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val nostrEvent: com.machankura.bennu.compose.database.model.NostrEvent?,

    @Relation(
        parentColumns = ["pubKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.machankura.bennu.compose.database.model.Profile?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val broadcastNostrEventRequest: com.machankura.bennu.compose.database.model.BroadcastNostrEventRequest?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val broadcastNostrEventReceipt: com.machankura.bennu.compose.database.model.BroadcastNostrEventReceipt?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["unsignedNostrEventId"]
    )
    val synchronizeNostrEventRequests: List<com.machankura.bennu.compose.database.model.SynchronizeNostrEventRequest>,

    )
