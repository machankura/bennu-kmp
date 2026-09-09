package com.it.curated.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class TranslationArtifactVersionDetailRoute(
    val activeUserPublicKey: String,
    val translationArtifactVersionId: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()
