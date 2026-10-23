package com.machankura.bennu.compose.database.repository

import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.IcebergKeySession
import com.machankura.bennu.compose.database.model.IcebergParticipantMessage
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.types.IcebergApprovalStep
import com.machankura.bennu.compose.managers.FrostSigningManager
import com.machankura.bennu.compose.database.model.FrostSigningSession
import com.machankura.bennu.compose.managers.GroupLightningOperatorManager
import com.machankura.bennu.compose.managers.GroupLightningWalletManager
import com.machankura.bennu.compose.managers.GroupWalletManager
import fr.acinq.phoenixd.conf.LSP
import com.machankura.bennu.compose.managers.IcebergRitualManager
import com.machankura.bennu.compose.nostr.lightning.GroupLightningEvents
import com.machankura.bennu.compose.repository.GroupLightningRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.Flow

class DatabaseGroupLightningRepository(private val database: BennuDatabase) : GroupLightningRepository {
    override suspend fun completedCeremony(chatRoomId: String): IcebergKeySession? = IcebergRitualManager.completedKey(database, chatRoomId)

    override suspend fun lightningWallet(chatRoomId: String, chain: Chain): GroupLightningWalletManager.Wallet? =
        GroupLightningWalletManager.walletFor(database, chatRoomId, chain)

    override suspend fun operator(chatRoomId: String, chain: Chain): GroupLightningOperatorManager.Operator? =
        GroupLightningOperatorManager.current(database, chatRoomId, chain)

    override fun lspNodeId(chain: Chain): HexKey? =
        runCatching { LSP.from(chain).walletParams.trampolineNode.id.toString() }.getOrNull()

    override suspend fun proposeWallet(localChatRoom: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray, chain: Chain): FrostSigningSession =
        GroupLightningWalletManager.proposeWallet(database, localChatRoom, userPublicKey, nostrPrivateKey, chain, requireNotNull(lspNodeId(chain)) { "No LSP is known for ${chain.name}" })

    override suspend fun nominateSelf(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain): FrostSigningSession =
        GroupLightningOperatorManager.nominateSelf(database, localChatRoom, userPublicKey, chain)

    override suspend fun stepDown(localChatRoom: LocalChatRoom, userPublicKey: HexKey, chain: Chain): FrostSigningSession =
        GroupLightningOperatorManager.stepDown(database, localChatRoom, userPublicKey, chain)

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
