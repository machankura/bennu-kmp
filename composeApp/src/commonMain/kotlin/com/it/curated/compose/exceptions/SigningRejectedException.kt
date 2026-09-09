package com.it.curated.compose.exceptions

class SigningRejectedException(message: String? = null, cause: Throwable? = null) : SignatureException(message, cause)