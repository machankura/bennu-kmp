package to.curare.compose.exceptions

class NetworkException(
    message: String? = null,
    cause: Throwable? = null
): RuntimeException(message, cause)