package to.curare.compose.ui.view.state

import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.GroupCuratedEntry
import to.curare.compose.nostr.GroupRelayList

sealed interface GroupCuratedEntriesUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * The entries this group has accepted into the lists it curates, most
         * recently signed first. Empty in a group that has accepted nothing,
         * which is every group until a quorum signs an entry.
         *
         * No capability beside it, because nothing on the screen needs one:
         * reading what the group decided takes no share of the key, and
         * neither does repeating its signature to a relay.
         */
        val groupCuratedEntries: List<GroupCuratedEntry> = emptyList(),
        /**
         * Where the group has said it lives, for "Broadcast all": each entry
         * goes where its own broadcast screen would send it before the member
         * changed anything, and that starts from these. See
         * `BroadcastGroupSignedEventViewModel.defaultRelaysFor`; the list's
         * own relays come off the entry, which carries its schema.
         */
        val relayLists: List<GroupRelayList> = emptyList(),
    ): GroupCuratedEntriesUIState

    data class Error(
        val message: String
    ): GroupCuratedEntriesUIState

    data object Loading: GroupCuratedEntriesUIState
}
