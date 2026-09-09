package com.it.curated.compose

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform