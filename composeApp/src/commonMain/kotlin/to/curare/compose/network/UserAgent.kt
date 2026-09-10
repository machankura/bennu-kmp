package to.curare.compose.network

object UserAgent {
    const val APP_NAME = "Curare"
    const val CLIENT_NAME = "Curare"
}

fun String.asClientTag(): Array<String> = arrayOf(
    "client",
    this
)