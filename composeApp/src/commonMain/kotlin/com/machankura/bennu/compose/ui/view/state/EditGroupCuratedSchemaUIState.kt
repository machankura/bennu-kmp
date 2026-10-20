package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.GroupCuratedSchema

sealed interface EditGroupCuratedSchemaUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * The schema being edited, as the group last signed it, or null for a new
         * one.
         *
         * Kept beside the working copy rather than replaced by it, because the
         * button proposes only when something differs -- and "differs" needs
         * both halves. Null also locks nothing: a new schema's identifier is the
         * member's to choose, and an existing one's is not.
         */
        val existing: GroupCuratedSchema? = null,
        /**
         * The relays a new schema starts with: where the group has said it
         * publishes, since that is where its list would be read.
         *
         * Only a new schema is seeded from these. An existing one has its own
         * relays signed into it, and those are the ones an edit starts from.
         */
        val defaultRelays: List<String> = emptyList(),
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the form writable and the button inert, for the same
         * reason the other editors do: drafting a schema costs nothing, and only
         * a share-holder can open the session that would sign it.
         */
        val canSign: Boolean = false,
    ): EditGroupCuratedSchemaUIState

    data class Error(
        val message: String
    ): EditGroupCuratedSchemaUIState

    data object Loading: EditGroupCuratedSchemaUIState
}
