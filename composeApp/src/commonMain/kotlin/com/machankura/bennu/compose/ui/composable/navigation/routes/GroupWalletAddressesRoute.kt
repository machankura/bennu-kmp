package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The group wallet's receive addresses: every one paid, the one being handed
 * out, and the next few -- pushed from the wallet screen's receive card.
 */
@Serializable
data class GroupWalletAddressesRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
): Route()
