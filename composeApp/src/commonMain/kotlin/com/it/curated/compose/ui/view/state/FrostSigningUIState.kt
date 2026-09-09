package com.it.curated.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.database.model.FrostSigningItem
import com.it.curated.compose.database.model.FrostSigningSession
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom

sealed interface FrostSigningUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,

        /** Null on the first emission, before the session has been collected. */
        val session: FrostSigningSession? = null,

        /**
         * The events the session signs, in the order its proposal fixed. Empty
         * on the first emission, and for a session whose proposal has not landed.
         */
        val items: List<FrostSigningItem> = emptyList(),

        /** Who has offered a nonce, so the screen can name who it is waiting on. */
        val offeredNonce: Set<HexKey> = emptySet(),

        /** Who has signed their part. */
        val signed: Set<HexKey> = emptySet(),
    ): FrostSigningUIState

    data class Error(
        val message: String
    ): FrostSigningUIState

    data object Loading: FrostSigningUIState
}
