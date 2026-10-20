package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom

sealed interface AddDialectUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,

        /**
         * Whether the group holds a shared key. A dialect is signed into
         * existence now rather than submitted, so a group without one cannot
         * make one here at all.
         */
        val canSign: Boolean = false,
    ): AddDialectUIState

    data class Error(
        val message: String
    ): AddDialectUIState

    data object Loading: AddDialectUIState
}
