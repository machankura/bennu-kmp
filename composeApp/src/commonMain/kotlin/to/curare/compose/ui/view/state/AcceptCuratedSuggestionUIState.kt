package to.curare.compose.ui.view.state

import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.CuratedSuggestion
import to.curare.compose.nostr.GroupCuratedSchema

sealed interface AcceptCuratedSuggestionUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** The list the entry would go into, as the group last signed it. */
        val schema: GroupCuratedSchema,
        /**
         * The suggestion being taken up, as the queue screen read it: the entry
         * keyed by the schema's fields, who suggested it, and whether the group
         * already has. The form starts as this, and the canonical entry points
         * back at it.
         */
        val suggestion: CuratedSuggestion,
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the form writable and the button inert, for the reason
         * the other editors give: reading and editing cost nothing, and only a
         * share-holder can open the session that would sign.
         */
        val canSign: Boolean = false,
    ): AcceptCuratedSuggestionUIState

    data class Error(
        val message: String
    ): AcceptCuratedSuggestionUIState

    data object Loading: AcceptCuratedSuggestionUIState
}
