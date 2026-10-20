package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class AddTranslationRoute(
    val activeUserPublicKey: String,
    val artifactId: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()
