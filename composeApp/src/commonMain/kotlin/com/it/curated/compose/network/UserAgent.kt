package com.it.curated.compose.network

object UserAgent {
    const val APP_NAME = "Curated"
    const val CLIENT_NAME = "Curated"
}

fun String.asClientTag(): Array<String> = arrayOf(
    "client",
    this
)