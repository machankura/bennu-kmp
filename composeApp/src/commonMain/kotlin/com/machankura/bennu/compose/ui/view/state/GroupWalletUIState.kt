package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.wallet.GroupWalletState
import com.machankura.bennu.compose.wallet.GroupSpend
import com.machankura.bennu.compose.wallet.lightning.GroupSweep
import com.machankura.bennu.compose.database.model.GroupTransaction
import com.machankura.bennu.compose.database.model.types.FrostSigningStage

sealed interface GroupWalletUIState {
    /**
     * A room whose group has not signed a wallet statement.
     *
     * [account] is what a statement would carry, or null for a room with no key
     * at all -- there is nothing to open then. [canSign] gates the way in, for
     * the reason every proposing screen gives: `proposeSigning` throws for a
     * device holding no share.
     */
    data class NotOpened(
        val localChatRoom: LocalChatRoom,
        val account: GroupWalletDerivation.Account?,
        val canSign: Boolean,
    ): GroupWalletUIState

    /**
     * An open wallet.
     *
     * [walletState] is null in two different situations, told apart by
     * [canWatch]: before Electrum's first answer, and for a profile with no node
     * to watch the chain with at all.
     */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val wallet: GroupWallet,
        val walletState: GroupWalletState?,
        val canWatch: Boolean,
        val canSign: Boolean,
        /** The room's spends, newest first. */
        val spends: List<SpendRow> = emptyList(),
    ): GroupWalletUIState

    data class Error(
        val message: String
    ): GroupWalletUIState

    data object Loading: GroupWalletUIState
}

/** Where one of the room's spends has got to, as one fact a member reads. */
enum class SpendStatus {
    WAITING_FOR_SIGNATURES,
    ABANDONED,
    SIGNED,
    SENT,
    CONFIRMED,
    REPLACED;

    companion object {
        /**
         * The session decides until the group has signed; the chain decides after.
         * Sent means Electrum has reported it back -- a broadcast reports success
         * whatever the server said, so sending is not evidence of having been sent.
         */
        fun of(stage: FrostSigningStage?, transaction: GroupTransaction): SpendStatus = when {
            transaction.replacedByTxid != null -> REPLACED
            transaction.confirmedAt != null -> CONFIRMED
            transaction.seenAt != null -> SENT
            transaction.signedTransaction != null -> SIGNED
            stage == FrostSigningStage.FAILED -> ABANDONED
            else -> WAITING_FOR_SIGNATURES
        }
    }
}

/** One of the room's spends, as the wallet screen lists it. */
data class SpendRow(
    val transaction: GroupTransaction,
    val status: SpendStatus,
    /** The spend, checked again; null where this device cannot check it, or for a sweep. */
    val spend: GroupSpend.Checked?,
    /** A sweep from the Lightning wallet into this one, checked again; null for a spend. */
    val sweep: GroupSweep.Checked? = null,
) {
    /** Signed, never reported back: the one moment sending it again is worth anything. */
    val canSendAgain: Boolean get() = status == SpendStatus.SIGNED

    /** Signed and not yet decided by a block: RBF is still possible. */
    val canBump: Boolean get() = (status == SpendStatus.SIGNED || status == SpendStatus.SENT) && spend != null
}
