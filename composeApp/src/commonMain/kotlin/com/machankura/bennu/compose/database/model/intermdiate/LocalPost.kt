package com.machankura.bennu.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation
import com.machankura.bennu.compose.database.model.Post
import com.machankura.bennu.compose.database.model.Profile

data class LocalPost(
    @Embedded val post: Post,
    @Relation(
        parentColumns = ["profilePublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: Profile
)
