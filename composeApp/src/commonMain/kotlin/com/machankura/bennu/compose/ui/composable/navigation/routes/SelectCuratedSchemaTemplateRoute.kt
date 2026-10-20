package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The first step of adding a curated schema: which shape the list starts from.
 *
 * Pushed from the group's schemas screen, and pushing the editor in turn with
 * the chosen template's key -- so that back from the editor is this screen and
 * a different shape, the way curare.to's `/new` page is under its editor.
 * There is no template to carry: the choice is made here and leaves as an
 * argument to `EditGroupCuratedSchemaRoute`.
 */
@Serializable
data class SelectCuratedSchemaTemplateRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
