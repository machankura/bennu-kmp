package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation

data class LocalChatMessage(
    @Embedded val chatMessage: com.it.curated.compose.database.model.ChatMessage,

    @Relation(
        parentColumns = ["senderPublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: com.it.curated.compose.database.model.Profile?,

    @Relation(
        parentColumns = ["id"],
        entityColumns = ["chatMessageId"]
    )
    val chatMessageBroadcastNostrEventReceiptRelation: com.it.curated.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation? = null,


    @Relation(
        parentColumns = ["id"],
        entityColumns = ["chatMessageId"]
    )
    val chatMessageBroadcastNostrEventRequestRelation: com.it.curated.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation? = null,


    @Relation(
        parentColumns = ["id"],
        entityColumns = ["chatMessageId"]
    )
    val chatMessageNostrEventRelation: com.it.curated.compose.database.model.ChatMessageNostrEventRelation? = null,
)
