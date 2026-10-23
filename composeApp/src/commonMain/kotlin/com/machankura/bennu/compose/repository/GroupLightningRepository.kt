package com.machankura.bennu.compose.repository

import com.machankura.bennu.compose.database.model.FrostSigningSession
import com.machankura.bennu.compose.database.model.IcebergKeySession
import com.machankura.bennu.compose.database.model.GroupLightningInvoiceRow
import com.machankura.bennu.compose.database.model.GroupLightningPaymentRow
import com.machankura.bennu.compose.managers.GroupLightningOperatorManager
import com.machankura.bennu.compose.managers.GroupLightningReports
import com.machankura.bennu.compose.managers.GroupLightningWalletManager
import com.machankura.bennu.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.bennu.compose.database.model.IcebergParticipantMessage
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.types.IcebergApprovalStep
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow

/**
 * Reads, opens and approves a group's Lightning wallet: for now, the Iceberg ceremony its key comes
 * from (`docs/group-lightning-wallet.md`, Phase 5). Advancing a ceremony is the inbound path's job,
 * except that a step waiting on this device's owner waits until [approveCeremony].
 */
interface GroupLightningRepository {
    fun observeCeremony(sessionId: String): Flow<IcebergKeySession?>

    fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>>

    suspend fun ceremonyMessages(sessionId: String): List<IcebergParticipantMessage>

    suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep?

    /** The thresholds a ceremony in [chatRoomId] could use, smallest first; empty when the room cannot have one. */
    suspend fun ceremonyThresholds(chatRoomId: String): List<Int>

    /** How many people hold the onchain wallet's key, and so would be in a ceremony; 0 for a room without one. */
    suspend fun keyHolderCount(chatRoomId: String): Int

    /** Whether [chatRoomId] has opened the onchain wallet a Lightning wallet on [chain] would close to. */
    suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean

    suspend fun proposeCeremony(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, threshold: Int, chain: Chain): IcebergKeySession

    suspend fun approveCeremony(localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray)

    /** The newest completed Lightning wallet key this device holds a share of, or null. */
    suspend fun completedCeremony(chatRoomId: String): IcebergKeySession?

    /** The wallet the room agreed on [chain], as this device can check it, or null. */
    suspend fun lightningWallet(chatRoomId: String, chain: Chain): GroupLightningWalletManager.Wallet?

    suspend fun operator(chatRoomId: String, chain: Chain): GroupLightningOperatorManager.Operator?

    /** The LSP a Lightning wallet on [chain] opens channels with, or null for a network with none. */
    fun lspNodeId(chain: Chain): HexKey?

    suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession

    suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain): FrostSigningSession

    suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain): FrostSigningSession

    /** The operator's newest snapshot of the wallet on [chain], each channel checked against this device's ledger. */
    fun observeReport(chatRoomId: String, chain: Chain): Flow<GroupLightningReports.Report?>

    /** Every payment any snapshot has reported, newest first. */
    fun observePayments(chatRoomId: String, chain: Chain): Flow<List<GroupLightningPaymentRow>>

    /** Asks the operator's node for an invoice; returns the request id its answer will carry. */
    suspend fun requestInvoice(localChatRoom: LocalChatRoom, userPublicKey: HexKey, wallet: GroupLightningWalletManager.Wallet, amountMsat: Long?, description: String): String

    /** The operator's invoice for [requestId], once it has arrived and been checked. */
    fun observeInvoice(chatRoomId: String, chain: Chain, requestId: String): Flow<GroupLightningInvoiceRow?>

    /** Proposes [pay] to the group; the operator's node pays it once a quorum signs. */
    suspend fun proposePayment(localChatRoom: LocalChatRoom, userPublicKey: HexKey, pay: GroupLightningAuthorisationEvent.Pay): FrostSigningSession
}
