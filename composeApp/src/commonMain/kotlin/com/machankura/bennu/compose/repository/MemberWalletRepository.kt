package com.machankura.bennu.compose.repository

import com.machankura.bennu.compose.database.model.MemberWallet
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.MemberWalletManager
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * A member's guarded wallets, for the screens: asking a group to guard one, and
 * -- from the group's side -- the requests and certificates in a room.
 * `docs/member-guarded-wallets.md`. The key-holding identity is the
 * repository's, never the screen's: nothing above this layer touches a key.
 */
interface MemberWalletRepository {
    /** The groups [ownerPublicKey] is in that could guard a wallet: Marmot rooms, whether or not their key is known yet. */
    suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom>

    /** Why [chatRoomId] cannot guard a wallet for the active profile on [chain], or null when it can. */
    suspend fun refusal(chatRoomId: String, chain: Chain): MemberWalletManager.Refusal?

    /** How many groups a wallet guarded by [chatRoomId] would be guarded by, or null while its key is unknown. */
    suspend fun reachOf(chatRoomId: String): Int?

    /** Asks [chatRoomId] what it signs with, for a member who holds no key state. Whether it asked. */
    suspend fun askForGroupKey(chatRoomId: String): Boolean

    /** Makes the key, works out the leaves, stores the key, and asks [chatRoomId] to guard it. */
    suspend fun request(chatRoomId: String, chain: Chain, label: String?): MemberWallet

    /** Every guarded wallet of [ownerPublicKey]'s this device knows about, newest first. */
    suspend fun walletsOf(ownerPublicKey: HexKey): List<MemberWalletManager.MemberWalletRow>

    /** One of them, or null. */
    suspend fun walletOf(ownerPublicKey: HexKey, walletId: String): MemberWalletManager.MemberWalletRow?

    /** Asks the group again, with the request the wallet was first asked with. */
    suspend fun askAgain(walletId: String)

    /** Forgets a wallet's key -- refused once a group guards it. */
    suspend fun discard(walletId: String)

    /** The wallet's receive and change descriptors with its private key in them, or null when the key is not here. */
    suspend fun exportKey(walletId: String): Pair<String, String>?

    /** What [chatRoomId] is called on this device, or null when it has no room for it. */
    suspend fun roomName(chatRoomId: String): String?

    companion object {
        val NO_OP = object : MemberWalletRepository {
            override suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom> = emptyList()
            override suspend fun refusal(chatRoomId: String, chain: Chain): MemberWalletManager.Refusal? = MemberWalletManager.Refusal.CANNOT_SIGN
            override suspend fun reachOf(chatRoomId: String): Int? = null
            override suspend fun askForGroupKey(chatRoomId: String): Boolean = false
            override suspend fun request(chatRoomId: String, chain: Chain, label: String?): MemberWallet =
                throw UnsupportedOperationException("no repository")
            override suspend fun walletsOf(ownerPublicKey: HexKey): List<MemberWalletManager.MemberWalletRow> = emptyList()
            override suspend fun walletOf(ownerPublicKey: HexKey, walletId: String): MemberWalletManager.MemberWalletRow? = null
            override suspend fun askAgain(walletId: String) = Unit
            override suspend fun discard(walletId: String) = Unit
            override suspend fun exportKey(walletId: String): Pair<String, String>? = null
            override suspend fun roomName(chatRoomId: String): String? = null
        }
    }
}
