package com.machankura.bennu.compose.database.repository

import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.IcebergKeySession
import com.machankura.bennu.compose.database.model.IcebergParticipantMessage
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.types.IcebergApprovalStep
import com.machankura.bennu.compose.managers.FrostSigningManager
import com.machankura.bennu.compose.managers.GroupWalletManager
import com.machankura.bennu.compose.managers.IcebergRitualManager
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.repository.GroupLightningRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow

class DatabaseGroupLightningRepository(private val database: BennuDatabase) : GroupLightningRepository {
    override fun observeCeremony(sessionId: String): Flow<IcebergKeySession?> =
        database.icebergKeySessionDao().observeSessionById(sessionId)

    override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> =
        database.icebergKeySessionDao().observeSessionsForChatRoom(chatRoomId)

    override suspend fun ceremonyMessages(sessionId: String): List<IcebergParticipantMessage> =
        GroupLightningEvents.CEREMONY.flatMap { database.icebergKeySessionDao().getMessagesByKind(sessionId, it) }

    override suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep? =
        IcebergRitualManager.pendingApproval(database, session)

    override suspend fun ceremonyThresholds(chatRoomId: String): List<Int> =
        FrostSigningManager.completedKey(database, chatRoomId)?.let(IcebergRitualManager::thresholdsFor).orEmpty()

    override suspend fun keyHolderCount(chatRoomId: String): Int =
        FrostSigningManager.completedKey(database, chatRoomId)?.participantPublicKeySet()?.size ?: 0

    override suspend fun hasOnchainWallet(chatRoomId: String, chain: Chain): Boolean =
        GroupWalletManager.walletFor(database, chatRoomId, chain) != null

    override suspend fun proposeCeremony(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, threshold: Int, chain: Chain): IcebergKeySession =
        IcebergRitualManager.proposeCeremony(database, localChatRoom, userPublicKey, nostrPrivateKey, threshold, chain)

    override suspend fun approveCeremony(localChatRoom: LocalChatRoom, sessionId: String, step: IcebergApprovalStep, nostrPrivateKey: ByteArray) =
        IcebergRitualManager.approve(database, localChatRoom, sessionId, step, nostrPrivateKey)
}
