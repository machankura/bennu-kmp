package to.curare.compose.network.serialization

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal val SocketsJson = CommonJson


fun JsonObject?.asNostrEventOrNull(): to.curare.compose.database.model.NostrEvent? {
    return try {
        if (this != null) SocketsJson.decodeFromJsonElement(this) else null
    } catch (error: IllegalArgumentException) {
        Logger.withTag("JsonObject?.asNostrEventOrNull").w(error) { "Unable to map as NostrEvent." }
        this?.let(SocketsJson::encodeToString)?.let { Logger.withTag("JsonObject?.asNostrEventOrNull").w { it } }
        null
    }
}
