package com.it.curated.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class NostrEventDetailRoute(
    val activeUserPublicKey: String,
    val nostrEventId: String,
): Route()