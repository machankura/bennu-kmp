package com.it.curated.compose.exceptions

class NetworkException(
    message: String? = null,
    cause: Throwable? = null
): RuntimeException(message, cause)