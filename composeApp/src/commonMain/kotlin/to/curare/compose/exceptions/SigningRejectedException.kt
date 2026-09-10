package to.curare.compose.exceptions

class SigningRejectedException(message: String? = null, cause: Throwable? = null) : SignatureException(message, cause)