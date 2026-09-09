package com.it.curated.compose

import java.io.File
import java.util.Locale

/**
 * Android wraps a `Context`, which is where the database and files directories come from.
 * The jvm has no such object, so the location is carried explicitly.
 *
 * This is deliberately *not* the same class as `fr.acinq.phoenix.utils.PlatformContext`,
 * matching how android and ios keep the two apart -- `MainActivity` and
 * `MainViewController` each construct both. A desktop entry point should hand the same
 * directory to both so one install keeps one place on disk.
 */
actual class PlatformContext(
    val applicationDir: File = defaultCuratedDir(),
)

/**
 * The conventional per-user application data directory for the host os.
 *
 * Pointedly not `java.io.tmpdir`, which is what the abandoned Aux implementation used
 * behind a TODO: most systems clear it on reboot, and the chat history and marmot group
 * state live here.
 */
internal fun defaultCuratedDir(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return when {
        os.contains("win") ->
            (System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA"))
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it, APP_DIR_NAME) }
                ?: File(home, "AppData/Local/$APP_DIR_NAME")

        os.contains("mac") || os.contains("darwin") ->
            File(home, "Library/Application Support/$APP_DIR_NAME")

        else ->
            System.getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it, APP_DIR_NAME) }
                ?: File(home, ".local/share/$APP_DIR_NAME")
    }
}

private const val APP_DIR_NAME = "curated"
