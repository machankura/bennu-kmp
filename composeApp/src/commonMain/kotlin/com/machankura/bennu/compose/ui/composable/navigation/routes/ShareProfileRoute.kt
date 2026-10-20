package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class ShareProfileRoute(
    val activeUserPublicKey: String,
    val nostrEventId: String,
): Route()