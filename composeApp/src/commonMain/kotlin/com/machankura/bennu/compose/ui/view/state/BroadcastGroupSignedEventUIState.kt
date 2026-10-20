package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.GroupRelayList

sealed interface BroadcastGroupSignedEventUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** The event to send, as the group signed it. What goes on the wire is this, byte for byte. */
        val signedEvent: GroupSignedEvent,
        /**
         * Where the group has said it lives, which is what the relay picker starts
         * from: the General list's write relays when the group has agreed one, and
         * never a relay the group has blocked. See
         * `BroadcastGroupSignedEventViewModel.defaultRelaysFor`.
         */
        val relayLists: List<GroupRelayList> = emptyList(),
        /**
         * The lists the group curates, for an entry into one of them: the NIP
         * says a canonical entry goes to its schema's relays, and the entry
         * names its schema by coordinate rather than carrying the relays itself.
         */
        val schemas: List<GroupCuratedSchema> = emptyList(),
    ): BroadcastGroupSignedEventUIState

    data class Error(
        val message: String
    ): BroadcastGroupSignedEventUIState

    data object Loading: BroadcastGroupSignedEventUIState
}
