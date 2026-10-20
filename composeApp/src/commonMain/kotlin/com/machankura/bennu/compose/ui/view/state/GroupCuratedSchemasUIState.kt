package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.GroupCuratedSchema

sealed interface GroupCuratedSchemasUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * The lists this group curates, as the schemas it signed for them, most
         * recently signed first. Empty in a group that curates nothing, which
         * is every group until somebody proposes a schema.
         */
        val groupCuratedSchemas: List<GroupCuratedSchema> = emptyList(),
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the schemas readable -- and their queues, which take no
         * share to read -- and hides both ways into the editor, the card's and
         * the button's, for the reason `GroupNostrProfileUIState.Loaded.canSign`
         * gives.
         */
        val canSign: Boolean = false,
    ): GroupCuratedSchemasUIState

    data class Error(
        val message: String
    ): GroupCuratedSchemasUIState

    data object Loading: GroupCuratedSchemasUIState
}
