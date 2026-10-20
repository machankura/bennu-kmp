package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The editor for one of a group's curated schemas.
 *
 * [identifier] is the schema's `d`, and null opens the editor on a new one. It
 * is the identifier rather than the event id because kind 31889 is addressable:
 * an edit replaces the event under the same `d`, and the editor's job is to
 * propose that replacement, so the coordinate is what names the thing being
 * edited and the event is only its current version.
 *
 * [template] is the key of the `CuratedSchemaTemplate` a new schema starts
 * from -- what `SelectCuratedSchemaTemplateRoute` chose, and the same word
 * curare.to's editor takes as `?template=`. It means nothing on an edit, which
 * starts from the schema itself, and null on a new schema is the bare two
 * fields every list has.
 */
@Serializable
data class EditGroupCuratedSchemaRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?,
    val identifier: String?,
    val template: String?
): Route()
