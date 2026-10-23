package com.machankura.bennu.compose.nostr.wallet

import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlin.time.Instant

/**
 * A wallet the group has opened: its statement, and the account that statement
 * rederives to.
 *
 * There is no table. A wallet is a signed event plus a derivation, and every
 * address, every balance and every check below is recomputed from the room's
 * threshold key -- [account] is what [GroupWalletStatementEvent.Reading.verifiedAgainst]
 * returned, never what the statement said.
 *
 * Read off [GroupSignedEvent] and gated on [GroupSignedEvent.verifies], the way
 * `GroupRelayList` is: only an event the room itself authored is the group
 * speaking.
 */
data class GroupWallet(
    val signedEvent: GroupSignedEvent,
    val account: GroupWalletDerivation.Account,
) {
    val chain: Chain get() = account.chain

    /** When the group opened it. */
    val openedAt: Instant get() = signedEvent.createdAt

    companion object {
        /**
         * The wallet one signed event opens, or null when it does not open one.
         *
         * Four refusals: the wrong kind, a signature that is not the room's, tags
         * that do not read as a statement, and a statement [thresholdPublicKey]
         * does not rederive.
         */
        fun of(signedEvent: GroupSignedEvent, thresholdPublicKey: HexKey): GroupWallet? {
            if (signedEvent.kind != GroupWalletStatementEvent.KIND) return null
            if (!signedEvent.verifies()) return null

            val account = GroupWalletStatementEvent.read(signedEvent.tags, signedEvent.content)
                ?.verifiedAgainst(thresholdPublicKey)
                ?: return null

            return GroupWallet(signedEvent = signedEvent, account = account)
        }

        /**
         * The newest wallet the group opened on [chain] at [account], or null for
         * one it never has.
         *
         * Newest by the group's own timestamp with the id breaking a tie, for the
         * reason `GroupRelayList.newestAmong` gives: two devices reading the same
         * events in different orders have to agree.
         *
         * Two rules first, for a guarded account, each of which keeps a later
         * statement from moving every address of one already holding coins:
         *
         *  1. a guarded statement outranks a plain one -- an account opened guarded
         *     stays guarded, whatever a client that predates guardians signs later;
         *  2. among guarded ones the earliest decides the guardians, and a later one
         *     naming others is ignored. Changing who guards a wallet is another
         *     account.
         */
        fun newestAmong(
            signedEvents: List<GroupSignedEvent>,
            thresholdPublicKey: HexKey,
            chain: Chain,
            account: Long = 0L,
        ): GroupWallet? {
            val order = compareBy<GroupWallet>({ it.openedAt }, { it.signedEvent.id })
            val opened = signedEvents
                .mapNotNull { of(it, thresholdPublicKey) }
                .filter { it.chain == chain && it.account.account == account }

            val guarded = opened.filter { it.account.isGuarded }
            val first = guarded.minWithOrNull(order)
                ?: return opened.maxWithOrNull(order)

            return guarded.filter { it.account.guardians == first.account.guardians }.maxWithOrNull(order)
        }
    }
}
