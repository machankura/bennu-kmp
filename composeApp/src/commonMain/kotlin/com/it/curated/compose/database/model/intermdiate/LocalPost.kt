package com.it.curated.compose.database.model.intermdiate

import androidx.room3.Embedded
import androidx.room3.Relation
import com.it.curated.compose.database.model.Post
import com.it.curated.compose.database.model.Profile

data class LocalPost(
    @Embedded val post: Post,
    @Relation(
        parentColumns = ["profilePublicKey"],
        entityColumns = ["publicKey"]
    )
    val profile: Profile
)
