package to.curare.compose

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform