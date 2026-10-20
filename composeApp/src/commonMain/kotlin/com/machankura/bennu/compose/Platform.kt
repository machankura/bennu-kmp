package com.machankura.bennu.compose

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform