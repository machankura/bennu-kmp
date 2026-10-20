package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.CuratedSuggestion
import com.machankura.bennu.compose.nostr.GroupCuratedSchema

sealed interface CuratedSuggestionListUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** The list whose queue this is, as the group last signed it. */
        val schema: GroupCuratedSchema,
        /**
         * Where the queue was read from: the relays the schema names, or the
         * group's own where it names none. On the screen because a queue read
         * from the wrong relays is an empty queue with no way to tell it from
         * a list nobody has written to.
         */
        val relays: List<String> = emptyList(),
        /** Everything suggested to the list, newest first. */
        val suggestions: List<CuratedSuggestion> = emptyList(),
        /**
         * Whether the relays are still being asked.
         *
         * A pull through the sync queue has no end-of-stored-events the screen
         * can see, so this is bounded by time rather than by an answer: true
         * from the request going out until the first suggestion lands or the
         * wait runs out. While it is true and nothing has arrived, the screen
         * is looking rather than empty.
         */
        val isAsking: Boolean = false,
    ): CuratedSuggestionListUIState {
        val curatedCount: Int get() = suggestions.count { it.isCurated }
    }

    data class Error(
        val message: String
    ): CuratedSuggestionListUIState

    data object Loading: CuratedSuggestionListUIState
}
