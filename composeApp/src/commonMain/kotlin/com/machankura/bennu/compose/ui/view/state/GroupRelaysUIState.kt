package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.GroupRelayList

sealed interface GroupRelaysUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * Where the group has said it lives on nostr, one entry per relay set.
         *
         * Empty only in a room that has none of this to say. Every other case
         * has four entries, some of them unsigned; see
         * `ChatRepository.groupRelayLists`.
         */
        val groupRelayLists: List<GroupRelayList> = emptyList(),
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the lists readable and hides the way into the editor,
         * for the reason `GroupNostrProfileUIState.Loaded.canSign` gives.
         */
        val canSign: Boolean = false,
    ): GroupRelaysUIState

    data class Error(
        val message: String
    ): GroupRelaysUIState

    data object Loading: GroupRelaysUIState
}
