package com.machankura.bennu.compose.nostr.wallet

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * A member telling an ancestor group about a descendant's guarded wallet.
 *
 * The descendant's quorum signed a guarded `GroupWalletStatementEvent` in its
 * own room, and the ancestors whose keys are in it are not in that room. A
 * member who sits in both carries the statement across, whole, as the content of
 * one of these in the ancestor's MLS room:
 *
 * ```
 *  descendant's room   every member holds the signed 30331     "we keep a guarded wallet"
 *  member in both --[ 30333 carrying it ]-> the ancestor's room
 *  every ancestor device checks it on its own, and watches the wallet
 * ```
 *
 * The forwarder vouches for nothing. A device believes the statement inside for
 * the same reasons a descendant member does -- the descendant's room signed it,
 * a certificate it carries certifies that room, the xpub rederives from the key
 * the certificate names -- plus one of its own: its own guardian key in the
 * statement is the key its group's walk reaches. See `GuardianManager.verify`.
 *
 * No table: the row the inbound path files for every inner event is the record,
 * and each read checks it again.
 *
 * ### Never on a relay
 *
 * The statement inside is an xpub, which is every address and the whole
 * balance. Like the statement, this only ever lives inside an encryption a relay
 * cannot open: it is an MLS application message and nothing else.
 *
 * ### Why 30333
 *
 * The first free number past the transaction proposal (30332), beside the
 * statement it carries.
 */
object GuardedWalletNoticeEvent {
    val KIND: Kind = 30333

    /** The statement a notice carries, or null when its content is not an event. Nothing is verified here. */
    fun statementOf(notice: Event): Event? =
        notice.takeIf { it.kind == KIND }?.let { Event.fromJsonOrNull(it.content) }
}
