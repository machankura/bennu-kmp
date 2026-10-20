package com.machankura.bennu.compose.network.relays

import com.machankura.bennu.compose.network.sockets.NostrIncomingMessage

data class NostrPublishResult(
    val result: NostrIncomingMessage? = null,
    val error: Throwable? = null,
    val relayUrl: String,
)
