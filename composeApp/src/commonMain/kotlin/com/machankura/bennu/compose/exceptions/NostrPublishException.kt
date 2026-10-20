package com.machankura.bennu.compose.exceptions

class NostrPublishException(
    override val cause: Throwable? = null,
    override val message: String? = null,
) : RuntimeException()