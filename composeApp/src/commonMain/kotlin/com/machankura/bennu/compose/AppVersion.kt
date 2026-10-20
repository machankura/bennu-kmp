package com.machankura.bennu.compose

expect object AppVersion {
    val versionName: String
    val versionCode: String // iOS uses string for build number
}