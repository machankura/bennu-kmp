package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.nostr.wallet.GroupWallet
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * Where a room's wallet is read from: its threshold key, and the statement the
 * group signed opening a wallet under it.
 */
object GroupWalletManager {
    /**
     * The threshold key [chatRoomId] signs with, or null for a room without one.
     *
     * The room's verified key state first -- which every member who was in the
     * ceremony holds, whether or not they are a signer -- and the key this device
     * would sign with behind it, for a room whose state this device never filed.
     * Either way it is a key the room's id rederives from, so a wallet read under
     * it is the room's.
     */
    suspend fun thresholdKeyFor(database: BennuDatabase, chatRoomId: String): HexKey? =
        GroupKeyStateManager.keyStateFor(database, chatRoomId)
            ?.takeIf { it.verifies() }
            ?.thresholdPublicKey
            ?: FrostSigningManager.completedKey(database, chatRoomId)?.thresholdPublicKey

    /** The account [chatRoomId] would open on [chain], whether or not it has: what a statement proposing it carries. */
    suspend fun accountFor(
        database: BennuDatabase,
        chatRoomId: String,
        chain: Chain,
        account: Long = 0L,
    ): GroupWalletDerivation.Account? =
        thresholdKeyFor(database, chatRoomId)?.let { GroupWalletDerivation.account(it, chain, account) }

    /** A guarded account a room could open, and the certificate that granted its guardians, which the statement carries. */
    data class GuardedOpening(
        val account: GroupWalletDerivation.Account,
        val certificate: Event,
    )

    /**
     * The guarded account [chatRoomId] would open on [chain], or null for a room
     * whose certificate grants it no guardian keys -- a room nobody's subgroup,
     * or one certified before certificates carried them.
     *
     * Off the verified key state, the one place a certificate this device
     * believes is kept. A member who holds no key state cannot open one; that
     * member cannot sign, either.
     */
    suspend fun guardedAccountFor(
        database: BennuDatabase,
        chatRoomId: String,
        chain: Chain,
        account: Long = 0L,
    ): GuardedOpening? {
        val thresholdPublicKey = thresholdKeyFor(database, chatRoomId) ?: return null
        val certificate = GroupKeyStateManager.keyStateFor(database, chatRoomId)
            ?.takeIf { it.verifies() }
            ?.birthCertificateJson
            ?.let { Event.fromJsonOrNull(it) }
            ?: return null
        val guardians = SubgroupBirthCertificateEvent.parseGuardians(certificate.tags)
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return GuardedOpening(GroupWalletDerivation.account(thresholdPublicKey, chain, account, guardians), certificate)
    }

    /**
     * The wallet [chatRoomId] has opened on [chain] under [thresholdPublicKey], or
     * null when the room signed no statement that key rederives.
     *
     * The form a signing session uses: it names the ceremony it signs under, and
     * a statement is only that ceremony's wallet if that ceremony's key reaches it.
     */
    suspend fun walletFor(
        database: BennuDatabase,
        chatRoomId: String,
        thresholdPublicKey: HexKey,
        chain: Chain,
        account: Long,
    ): GroupWallet? = GroupWallet.newestAmong(
        signedEvents = database.groupSignedEventDao()
            .getByChatRoomIdAndKind(chatRoomId, GroupWalletStatementEvent.KIND),
        thresholdPublicKey = thresholdPublicKey,
        chain = chain,
        account = account,
    )

    /** The wallet [chatRoomId] has opened on [chain], or null when the group has not opened one. */
    suspend fun walletFor(
        database: BennuDatabase,
        chatRoomId: String,
        chain: Chain,
        account: Long = 0L,
    ): GroupWallet? {
        val thresholdPublicKey = thresholdKeyFor(database, chatRoomId) ?: return null

        return GroupWallet.newestAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, GroupWalletStatementEvent.KIND),
            thresholdPublicKey = thresholdPublicKey,
            chain = chain,
            account = account,
        )
    }
}
