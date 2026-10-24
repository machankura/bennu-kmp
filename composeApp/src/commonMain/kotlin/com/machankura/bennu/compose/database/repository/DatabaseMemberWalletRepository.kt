package com.machankura.bennu.compose.database.repository

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.MemberWallet
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.identity.Identity
import com.machankura.bennu.compose.managers.MemberWalletManager
import com.machankura.bennu.compose.repository.MemberWalletRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DatabaseMemberWalletRepository(
    private val database: BennuDatabase,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
) : MemberWalletRepository {
    private val logger = Logger.withTag(TAG)

    override suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom> =
        database.chatRoomDao().getChatRoomListByUserPublicKey(ownerPublicKey)
            .filter { it.chatRoom.mlsGroupState != null }

    override suspend fun refusal(chatRoomId: String, chain: Chain): MemberWalletManager.Refusal? = try {
        MemberWalletManager.refusal(database, chatRoomId, canSign = activeIdentity.value?.canSign == true, chain = chain)
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId can guard a wallet", e)
        MemberWalletManager.Refusal.KEY_UNKNOWN
    }

    override suspend fun reachOf(chatRoomId: String): Int? = try {
        MemberWalletManager.reachOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading how far $chatRoomId's guard reaches", e)
        null
    }

    override suspend fun askForGroupKey(chatRoomId: String): Boolean {
        val identity = activeIdentity.value ?: return false
        return MemberWalletManager.askForGroupKey(database, chatRoomId, identity.nostrPublicKey)
    }

    override suspend fun request(chatRoomId: String, chain: Chain, label: String?): MemberWallet {
        val key = activeIdentity.value?.nostrPrivateKey ?: throw IllegalStateException("This profile cannot sign")
        return MemberWalletManager.request(database, chatRoomId, key, chain, label)
    }

    companion object {
        private const val TAG = "DatabaseMemberWalletRepository"
    }
}
