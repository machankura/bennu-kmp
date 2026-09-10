package to.curare.compose.ui.view.state

import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.GroupRelayList

sealed interface EditGroupRelaysUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * What the group has signed for each set, which is what an edit is measured
         * against.
         *
         * Kept beside the working copy rather than replaced by it, because the
         * button only proposes the sets that actually differ -- and "differ" needs
         * both halves. See `EditGroupRelaysViewModel.changedSets`.
         */
        val signed: List<GroupRelayList> = emptyList(),
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the lists readable and the button inert: where the group
         * says it lives is worth reading whoever is looking, and proposing a change
         * to it is a signature only a share-holder can start.
         */
        val canSign: Boolean = false,
    ): EditGroupRelaysUIState

    data class Error(
        val message: String
    ): EditGroupRelaysUIState

    data object Loading: EditGroupRelaysUIState
}
