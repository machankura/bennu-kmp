package com.machankura.bennu.compose.repository

import com.machankura.bennu.compose.database.model.IcebergKeySession
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
}
