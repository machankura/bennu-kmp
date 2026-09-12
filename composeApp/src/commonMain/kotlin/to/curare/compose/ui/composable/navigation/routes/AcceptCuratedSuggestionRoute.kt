package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The form for taking one suggestion into a list the group curates: the entry
 * as it was suggested, open for the group's edits, and a button that asks the
 * group to sign it as the list's own.
 *
 * [identifier] is the schema's `d`, for the reason `CuratedSuggestionListRoute`
 * gives; [suggestionId] is the suggestion's event id rather than its
 * coordinate, because the canonical entry pins the exact version the group
 * read -- the NIP's `e` tag -- and the id is what names a version.
 */
@Serializable
data class AcceptCuratedSuggestionRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?,
    val identifier: String,
    val suggestionId: String
): Route()
