package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class WriteNewNoteRoute(
    val activeUserPublicKey: String,
    val inReplyToEventId: String? = null,
    val quotedEventId: String? = null,
): Route()