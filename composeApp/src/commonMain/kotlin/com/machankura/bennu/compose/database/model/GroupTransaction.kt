package com.machankura.bennu.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One transaction from a group's wallet, from the moment it is proposed.
 *
 * Written whole at acceptance and then filled in: the signed transaction when
 * the session completes, and when it was broadcast and seen confirmed after.
 * It can be written whole because a segwit txid excludes the witnesses -- the
 * [txid] is fixed before anybody signs, and it is what the transcript, the
 * proposal list and the chain all key on.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = FrostSigningSession::class,
            parentColumns = ["id"],
            childColumns = ["frostSigningSessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chatRoomId"),
        Index("txid"),
    ],
)
data class GroupTransaction(
    @PrimaryKey
    val frostSigningSessionId: String,

    val chatRoomId: String,

    /** The network, as `GroupWalletStatementEvent.chainName` writes it. */
    val chain: String,

    /** The wallet account the inputs are under. From the room's statement, never from the proposal. */
    val account: Long,

    /**
     * The PSBT as proposed, base64. Write-once: it is what every item's sighash
     * was computed from, and a second proposal under the same session id that
     * differs anywhere in its unsigned transaction is ignored.
     */
    val psbt: String,

    /** The unsigned transaction's id -- which is the signed one's too. */
    val txid: String,

    /** The finished transaction, hex, once the session completes and it has passed `correctlySpends`. */
    val signedTransaction: String? = null,

    /**
     * When this device last handed it to Electrum. Not when it reached the
     * network: Electrum's broadcast reports success whatever the server said,
     * so [seenAt] is the only evidence that it did.
     */
    val broadcastAt: Instant? = null,

    /** When this device's wallet first saw the transaction -- in the mempool or in a block. */
    val seenAt: Instant? = null,

    /** When its first confirmation was seen. */
    val confirmedAt: Instant? = null,

    /** The txid of a later spend of the same coins that confirmed instead, if one did. */
    val replacedByTxid: String? = null,

    val createdAt: Instant = Clock.System.now(),
)
