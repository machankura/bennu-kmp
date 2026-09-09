package com.it.curated.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactDetailRoute(
    val activeUserPublicKey: String,
    val artifactId: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()
