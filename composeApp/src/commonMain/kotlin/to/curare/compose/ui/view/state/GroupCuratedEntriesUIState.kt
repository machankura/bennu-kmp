package to.curare.compose.ui.view.state

import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.GroupCuratedEntry

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
    ): GroupCuratedEntriesUIState

    data class Error(
        val message: String
    ): GroupCuratedEntriesUIState

    data object Loading: GroupCuratedEntriesUIState
}
