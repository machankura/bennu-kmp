package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The queue of one list the group curates: everything anyone has suggested to
 * the schema at [identifier], read off the relays the schema names.
 *
 * Named by the schema's `d` for the reason `EditGroupCuratedSchemaRoute` is:
 * the coordinate is what every suggestion replies to, and the group re-signing
 * the schema changes the event without changing the queue.
 */
@Serializable
data class CuratedSuggestionListRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?,
    val identifier: String
): Route()
