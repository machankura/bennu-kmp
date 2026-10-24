package com.machankura.bennu.compose.ui.view.state

import com.machankura.bennu.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.managers.GroupWalletManager
import com.machankura.bennu.compose.managers.GuardianManager
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.utils.sum
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.wallet.GroupWalletState
import com.machankura.bennu.compose.wallet.GroupSpend
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
     *
     * [guarded] is the guarded account a subgroup could open instead, when its
     * birth certificate grants it guardian keys and guarded wallets are offered
     * on this network -- see `docs/group-wallet-guardians.md`.
     *
     * [policy] is what the room's own certificate decided about its wallet, or
     * null for a room with none -- `docs/subgroup-wallet-policy.md`. A decided
     * policy leaves one way in: [guarded] alone for *guarded*, the plain
     * [account] alone for *unguarded*, which [offersPlain] and [guarded] say.
     */
    data class NotOpened(
        val localChatRoom: LocalChatRoom,
        val account: GroupWalletDerivation.Account?,
        val canSign: Boolean,
        val guarded: GroupWalletManager.GuardedOpening? = null,
        val policy: WalletPolicyReading? = null,
        /** What this device calls each guardian's room, by room id, for the ones it holds. */
        val guardianNames: Map<String, String> = emptyMap(),
        /** Whether a plain wallet may be opened: false only when the parent decided this one is guarded. */
        val offersPlain: Boolean = true,
        /** Guarded wallets of groups below this one. A group with no wallet of its own can still guard others'. */
        val guardedWallets: List<GuardedWalletRow> = emptyList(),
        /** Guarded statements this device can carry to the groups above. */
        val shareable: List<GuardianManager.Shareable> = emptyList(),
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
        /** Guarded wallets of groups below this one. */
        val guardedWallets: List<GuardedWalletRow> = emptyList(),
        /** Guarded statements this device can carry to the groups above. */
        val shareable: List<GuardianManager.Shareable> = emptyList(),
        /** What this device calls each guardian's room, by room id, for the ones it holds. */
        val guardianNames: Map<String, String> = emptyMap(),
    ): GroupWalletUIState

    data class Error(
        val message: String
    ): GroupWalletUIState

    data object Loading: GroupWalletUIState
}

/** A descendant's guarded wallet, as an ancestor's wallet screen lists it. */
data class GuardedWalletRow(
    val wallet: GuardianManager.GuardedWallet,
    /** Null before Electrum's first answer, and for a profile that cannot watch the chain. */
    val walletState: GroupWalletState?,
) {
    /**
     * What this group could spend now: coins confirmed at least as deep as its
     * own leaf's lock -- not the default's, since the group that granted the
     * leaf chose it. Null while the chain is not known.
     */
    val withinReach: Satoshi?
        get() = walletState?.let { state ->
            state.utxos.filter { state.confirmations(it) >= wallet.delay }.map { it.amount }.sum()
        }
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
    /** The spend, checked again; null where this device cannot check it. */
    val spend: GroupSpend.Checked?,
) {
    /** Signed, never reported back: the one moment sending it again is worth anything. */
    val canSendAgain: Boolean get() = status == SpendStatus.SIGNED

    /** Signed and not yet decided by a block: RBF is still possible. */
    val canBump: Boolean get() = (status == SpendStatus.SIGNED || status == SpendStatus.SENT) && spend != null
}
