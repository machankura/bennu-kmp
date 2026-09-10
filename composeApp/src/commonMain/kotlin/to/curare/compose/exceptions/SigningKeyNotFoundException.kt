package to.curare.compose.exceptions

class SigningKeyNotFoundException(message: String? = null, cause: Throwable? = null) : SignatureException(
    message,
    cause,
)
