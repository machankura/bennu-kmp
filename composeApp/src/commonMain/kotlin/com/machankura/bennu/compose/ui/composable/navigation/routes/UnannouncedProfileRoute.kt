package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class UnannouncedProfileRoute(
    val nostrEventId: String,
): Route()