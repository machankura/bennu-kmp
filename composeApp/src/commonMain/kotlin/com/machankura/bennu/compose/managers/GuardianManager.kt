package com.machankura.bennu.compose.managers

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.bennu.compose.wallet.GroupSpend
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.Chain
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What an ancestor group knows of the guarded wallets beneath it, and how it
 * comes to know.
 *
 * A descendant opens a guarded wallet in its own room. Its ancestors are not in
 * that room, so a member who sits in both carries the signed statement across
 * as a [GuardedWalletNoticeEvent]; every device in the ancestor's room checks it
 * for itself, and from then on watches the wallet and can propose spending from
 * it through its own leaf. See `docs/group-wallet-guardians.md`, "How an
 * ancestor finds the coins".
 *
 * A notice forwarded into a middle group can be forwarded again from there, so
 * a root needs a member in common with each generation, not with the grandchild.
 */
object GuardianManager {
    private const val TAG = "GuardianManager"

    private val logger = Logger.withTag(TAG)

    /** A descendant's guarded wallet, as one of its ancestors holds it. */
    data class GuardedWallet(
        /** The ancestor holding it: the room it was told to. */
        val ancestorChatRoomId: String,
        val descendantChatRoomId: String,
        /** The descendant's signed statement, whole. */
        val statement: Event,
        /** The account the statement opens, rederived from the descendant's key. */
        val account: GroupWalletDerivation.Account,
        /** The descendant's birth certificate, carried in the statement. */
        val certificate: Event,
        /** The rooms the ancestor walks from its own child down to the descendant -- `GuardianDerivation.lineage`. */
        val lineage: List<String>,
    ) {
        /** What the descendant's parent certified it as. The founding name: a rename does not reach it. */
        val name: String? get() = SubgroupBirthCertificateEvent.parseName(certificate.tags)

        val chain: Chain get() = account.chain

        val openedAt: Instant get() = Instant.fromEpochSeconds(statement.createdAt)

        /** Where the ancestor sits among the guardians: whose leaf, in every address, is its. */
        val position: Int get() = account.guardians.indexOfFirst { it.chatRoomId.equals(ancestorChatRoomId, ignoreCase = true) }

        /** The ancestor as the spender of a spend from this wallet, signing as [thresholdPublicKey] -- null to only build one. */
        fun spender(thresholdPublicKey: HexKey? = null): GroupSpend.Spender.Guardian =
            GroupSpend.Spender.Guardian(position = position, lineage = lineage, thresholdPublicKey = thresholdPublicKey, name = name)
    }

    /**
     * [statement] as a guarded wallet [ancestorChatRoomId] guards, or null when
     * it is not one -- believed on its own merits, never on the word of whoever
     * forwarded it:
     *
     *  1. it is a wallet statement, and the room it is authored by signed it;
     *  2. it is guarded, and carries the certificate its guardians came from;
     *  3. the certificate's key makes that room, and the statement's xpub, tree
     *     and descriptors all rederive from it -- `Reading.verifiedAgainst`, which
     *     also checks the certificate certifies the room and grants exactly those
     *     guardians;
     *  4. the ancestor is among the guardians, and the key the statement names
     *     for it is the key [ancestorThresholdPublicKey]'s guardian root reaches
     *     down the line of descent the guardians name. A key it does not reach is
     *     a leaf this group cannot sign, whatever the statement calls it.
     */
    fun verify(statement: Event, ancestorChatRoomId: String, ancestorThresholdPublicKey: HexKey): GuardedWallet? = runCatching {
        if (statement.kind != GroupWalletStatementEvent.KIND) return null
        val descendantChatRoomId = statement.pubKey
        if (!GroupKeyStateEvent.isSignedByRoom(statement, descendantChatRoomId)) return null

        val reading = GroupWalletStatementEvent.read(statement.tags, statement.content) ?: return null
        if (reading.guardians.isEmpty()) return null
        val certificate = reading.certificate ?: return null

        val descendantKey = SubgroupBirthCertificateEvent.parseThresholdPublicKey(certificate.tags) ?: return null
        if (!SharedKeyDerivation.marmotGroupId(descendantKey).equals(descendantChatRoomId, ignoreCase = true)) return null
        val account = reading.verifiedAgainst(descendantKey) ?: return null

        val lineage = GuardianDerivation.lineage(account.guardians, descendantChatRoomId, ancestorChatRoomId) ?: return null
        val named = account.guardians.first { it.chatRoomId.equals(ancestorChatRoomId, ignoreCase = true) }
        val walked = GuardianDerivation.root(ancestorThresholdPublicKey).extendedPublicKey
            .derivePublicKey(lineage.map(GuardianDerivation::childIndex))
        if (walked.publicKey != named.publicKey || walked.chaincode != named.chainCode) return null

        GuardedWallet(
            ancestorChatRoomId = ancestorChatRoomId,
            descendantChatRoomId = descendantChatRoomId,
            statement = statement,
            account = account,
            certificate = certificate,
            lineage = lineage,
        )
    }.getOrNull()

    /**
     * Every guarded wallet [ancestorChatRoomId] has been told about and can sign
     * for, one per descendant account on each network.
     *
     * Read off the notices the room's inbound path filed, checked again each
     * time. Where two statements describe one account, the rules
     * `GroupWallet.newestAmong` applies to the descendant's own copy apply here:
     * the earliest decides the guardians and a later one naming others is
     * ignored, so a forwarder cannot move the addresses an ancestor watches.
     */
    suspend fun guardedWalletsOf(database: BennuDatabase, ancestorChatRoomId: String): List<GuardedWallet> {
        val thresholdPublicKey = GroupWalletManager.thresholdKeyFor(database, ancestorChatRoomId) ?: return emptyList()
        val order = compareBy<GuardedWallet>({ it.openedAt }, { it.statement.id })

        return database.marmotInnerEventDao()
            .getByChatRoomAndKinds(ancestorChatRoomId, listOf(GuardedWalletNoticeEvent.KIND))
            .mapNotNull { row -> Event.fromJsonOrNull(row.content) }
            .mapNotNull { verify(it, ancestorChatRoomId, thresholdPublicKey) }
            .groupBy { Triple(it.descendantChatRoomId, it.chain, it.account.account) }
            .mapNotNull { (_, statements) ->
                val first = statements.minWithOrNull(order) ?: return@mapNotNull null
                statements.filter { it.account.guardians == first.account.guardians }.maxWithOrNull(order)
            }
            .sortedBy { it.name ?: it.descendantChatRoomId }
    }

    /** The guarded wallet [ancestorChatRoomId] holds for [descendantChatRoomId] on [chain] at [account], or null. */
    suspend fun guardedWalletFor(
        database: BennuDatabase,
        ancestorChatRoomId: String,
        descendantChatRoomId: String,
        chain: Chain,
        account: Long,
    ): GuardedWallet? = guardedWalletsOf(database, ancestorChatRoomId).firstOrNull {
        it.descendantChatRoomId.equals(descendantChatRoomId, ignoreCase = true) && it.chain == chain && it.account.account == account
    }

    /** A guarded statement this device can carry upward, and the rooms it can carry it into. */
    data class Shareable(
        val statement: Event,
        /** What the statement's room was certified as, for the screen. */
        val name: String?,
        val rooms: List<LocalChatRoom>,
    )

    /**
     * The guarded statements this device can tell to groups above [chatRoomId]:
     * the room's own guarded wallet, and every one the room was itself told
     * about -- passed on a generation further. Each with the guardian rooms this
     * device is a member of and has not told yet.
     */
    suspend fun shareableFrom(database: BennuDatabase, chatRoomId: String, chain: Chain): List<Shareable> {
        val own = GroupWalletManager.walletFor(database, chatRoomId, chain)
            ?.takeIf { it.account.isGuarded }
            ?.let { wallet ->
                val statement = wallet.signedEvent.toEvent()
                val certificate = GroupWalletStatementEvent.read(statement.tags, statement.content)?.certificate
                statement to (certificate?.let { SubgroupBirthCertificateEvent.parseName(it.tags) })
            }
        val passedOn = guardedWalletsOf(database, chatRoomId)
            .filter { it.chain == chain }
            .map { it.statement to it.name }

        return (listOfNotNull(own) + passedOn).mapNotNull { (statement, name) ->
            val rooms = targetsFor(database, statement, excluding = chatRoomId)
            rooms.takeIf { it.isNotEmpty() }?.let { Shareable(statement, name, it) }
        }
    }

    /**
     * The guardian rooms of [statement] this device can post into and has not
     * told it yet. Only Marmot rooms: a guardian is a group with a key, and a
     * group with a key has an MLS room.
     */
    private suspend fun targetsFor(database: BennuDatabase, statement: Event, excluding: String): List<LocalChatRoom> {
        val guardians = GroupWalletStatementEvent.read(statement.tags, statement.content)?.guardians.orEmpty()

        return guardians
            .map { it.chatRoomId }
            .filterNot { it.equals(excluding, ignoreCase = true) }
            .mapNotNull { database.chatRoomDao().findChatRoomById(it) }
            .filter { it.chatRoom.mlsGroupState != null }
            .filterNot { room -> alreadyTold(database, room.chatRoom.id, statement.id) }
    }

    private suspend fun alreadyTold(database: BennuDatabase, chatRoomId: String, statementId: HexKey): Boolean =
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(GuardedWalletNoticeEvent.KIND))
            .any { Event.fromJsonOrNull(it.content)?.id == statementId }

    /**
     * Tells every room in [rooms] about [statement], as [userPublicKey]: one
     * notice each, queued for the outbound pipeline the way every inner event
     * is. Returns how many were queued.
     */
    suspend fun share(
        database: BennuDatabase,
        statement: Event,
        rooms: List<LocalChatRoom>,
        userPublicKey: HexKey,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): Int {
        val content = statement.toJson()
        val tags = emptyArray<Array<String>>()

        rooms.forEach { room ->
            val id = EventHasher.hashId(userPublicKey, createdAt, GuardedWalletNoticeEvent.KIND, tags, content)
            database.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = id,
                    publicKey = userPublicKey,
                    kind = GuardedWalletNoticeEvent.KIND,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = tags,
                    content = content,
                    chatRoomId = room.chatRoom.id,
                )
            )
            logger.i("Told ${room.chatRoom.id} about ${statement.pubKey}'s guarded wallet")
        }

        return rooms.size
    }

    /**
     * What a notice that arrived in [chatRoomId] says, for its transcript line --
     * or null when it is not one this room can believe, which gets no line: a
     * line saying a wallet was shared would read as the group being told
     * something it was not.
     */
    suspend fun describeNotice(database: BennuDatabase, chatRoomId: String, notice: Event): String? {
        val statement = GuardedWalletNoticeEvent.statementOf(notice) ?: return null
        val thresholdPublicKey = GroupWalletManager.thresholdKeyFor(database, chatRoomId) ?: return null
        val wallet = verify(statement, chatRoomId, thresholdPublicKey) ?: return null

        return wallet.name?.let { "Shared the guarded wallet of $it with this group." }
            ?: "Shared a subgroup's guarded wallet with this group."
    }
}
