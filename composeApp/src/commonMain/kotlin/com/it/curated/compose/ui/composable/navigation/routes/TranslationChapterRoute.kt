package com.it.curated.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class TranslationChapterRoute(
    val activeUserPublicKey: String,
    val translationChapterId: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()
