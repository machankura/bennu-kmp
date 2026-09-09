package com.it.curated.compose.exceptions

class SigningKeyNotFoundException(message: String? = null, cause: Throwable? = null) : SignatureException(
    message,
    cause,
)
