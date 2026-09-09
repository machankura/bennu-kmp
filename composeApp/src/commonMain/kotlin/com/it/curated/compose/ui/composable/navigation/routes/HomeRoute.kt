package com.it.curated.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class HomeRoute(
    val activeUserPublicKey: String
): Route()