package to.curare.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom

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

        /**
         * Who has refused. Named for the same reason the other two are: a
         * t-of-n session survives some number of refusals and not one more, so
         * which members those are is the difference between a session that is
         * one refusal from ending and one that is nowhere near.
         */
        val declined: Set<HexKey> = emptySet(),
    ): FrostSigningUIState

    data class Error(
        val message: String
    ): FrostSigningUIState

    data object Loading: FrostSigningUIState
}
