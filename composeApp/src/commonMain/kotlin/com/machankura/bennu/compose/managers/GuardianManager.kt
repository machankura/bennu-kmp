package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.nostr.subgroup.allows
import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.ChatMessage
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.bennu.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.bennu.compose.nostr.subgroup.tags.GuardianTag
import com.machankura.bennu.compose.wallet.GroupSpend
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.utils.sum
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

    /**
     * Whose guarded wallet it is: a subgroup's, under its quorum's key, or one
     * member's, under a key only they hold -- `docs/member-guarded-wallets.md`.
     * Either way [id] is what the line of descent ends in, so every guardian
     * signs for its leaf the same way.
     */
    sealed interface GuardedSubject {
        /** A room id, or a member wallet's `W`. */
        val id: String

        data class Subgroup(override val id: String, val name: String?) : GuardedSubject

        data class Member(
            override val id: String,
            val ownerPublicKey: HexKey,
            val label: String?,
            /** The member's profile name on this device, where one is known; filled where a database is to hand. */
            val ownerName: String? = null,
            /**
             * The group that directly guards it, by its founding name where this
             * device can check a birth certificate for it, else its room name here;
             * null when neither is known. Filled where a database is to hand.
             * `docs/member-wallet-guarding-group.md`, D2.
             */
            val guardianName: String? = null,
            /** The holding group's own child it sits under, named the same way; null when the holder guards it directly. */
            val branchName: String? = null,
        ) : GuardedSubject
    }

    /** A descendant's guarded wallet, as one of its ancestors holds it. */
    data class GuardedWallet(
        /** The ancestor holding it: the room it was told to. */
        val ancestorChatRoomId: String,
        /** [subject]'s id: the descendant's room, or a member wallet's `W`. */
        val descendantChatRoomId: String,
        /**
         * What the ancestor believes it on, whole: a subgroup's signed statement,
         * or the member wallet certificate the guarding group signed.
         */
        val statement: Event,
        /** The account the statement opens, rederived from the descendant's key. */
        val account: GroupWalletDerivation.Account,
        /** A subgroup's birth certificate, carried in the statement -- or, for a member, the certificate itself. */
        val certificate: Event,
        /** The rooms the ancestor walks from its own child down to the descendant -- `GuardianDerivation.lineage`. */
        val lineage: List<String>,
        val subject: GuardedSubject = GuardedSubject.Subgroup(descendantChatRoomId, SubgroupBirthCertificateEvent.parseName(certificate.tags)),
        /**
         * For a member's wallet: the birth certificate of the group that directly
         * guards it, checked, where this device holds one -- what a notice
         * carries upward so the groups above can name that group.
         * `docs/member-wallet-guarding-group.md`, D4.
         */
        val guardianCertificate: Event? = null,
    ) {
        /** What the descendant's parent certified it as, for a subgroup. The founding name: a rename does not reach it. */
        val name: String? get() = (subject as? GuardedSubject.Subgroup)?.name

        val chain: Chain get() = account.chain

        val openedAt: Instant get() = Instant.fromEpochSeconds(statement.createdAt)

        /** Where the ancestor sits among the guardians: whose leaf, in every address, is its. */
        val position: Int get() = account.guardians.indexOfFirst { it.chatRoomId.equals(ancestorChatRoomId, ignoreCase = true) }

        /**
         * How long a coin here waits before this ancestor can take it: the delay
         * on its own leaf, which the group that granted it chose and which two of
         * an ancestor's descendants can have set differently.
         * `docs/guardian-delay.md`.
         */
        val delay: Long get() = account.guardians[position].delay

        /**
         * The group that directly guards it: the nearest guardian, which for a
         * member's wallet is the group that signed its certificate --
         * `MemberWalletRequestEvent.verified` refuses a request whose nearest
         * guardian is not the group it asks. `docs/member-wallet-guarding-group.md`, D1.
         */
        val directGuardianChatRoomId: String get() = account.guardians.first().chatRoomId

        /** True when the ancestor holding it is the group that directly guards it. */
        val isGuardedHere: Boolean get() = position == 0

        /**
         * The ancestor's own child the wallet sits under -- the branch of its tree
         * -- or null when the ancestor guards it directly. The ancestor signed
         * that child's birth certificate, so it can always name it.
         */
        val branchChatRoomId: String? get() = lineage.takeIf { position > 0 }?.first()

        /** The ancestor as the spender of a spend from this wallet, signing as [thresholdPublicKey] -- null to only build one. */
        fun spender(thresholdPublicKey: HexKey? = null): GroupSpend.Spender.Guardian =
            GroupSpend.Spender.Guardian(position = position, lineage = lineage, thresholdPublicKey = thresholdPublicKey, name = displayName)

        /** How a spend from it names it: a subgroup's founding name, or `@` and a member's. */
        val displayName: String?
            get() = when (val subject = subject) {
                is GuardedSubject.Subgroup -> subject.name
                is GuardedSubject.Member -> "@" + (subject.ownerName ?: subject.ownerPublicKey.take(8))
            }
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
        if (statement.kind == MemberWalletCertificateEvent.KIND) return verifyMember(statement, ancestorChatRoomId, ancestorThresholdPublicKey)
        if (statement.kind != GroupWalletStatementEvent.KIND) return null
        val descendantChatRoomId = statement.pubKey
        if (!GroupKeyStateEvent.isSignedByRoom(statement, descendantChatRoomId)) return null

        val reading = GroupWalletStatementEvent.read(statement.tags, statement.content) ?: return null
        if (reading.guardians.isEmpty()) return null
        val certificate = reading.certificate ?: return null
        // A guarded wallet the certificate's own policy does not allow -- one it
        // contradicts, or decided unguarded -- is not one an honest descendant
        // opens, so it is not one to list. docs/subgroup-wallet-policy.md.
        if (!SubgroupBirthCertificateEvent.parseWalletPolicy(certificate.tags).let { it != null && it.allows(guarded = true) }) return null

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
     * [certificate] as a member's guarded wallet [ancestorChatRoomId] guards, or
     * null when it is not one: the guarding group signed it over the member's
     * own request (`MemberWalletCertificateEvent.verify`), and -- rule 4 of
     * [verify] -- the key it names for the ancestor is the key the ancestor's
     * guardian root reaches down the line of descent, which ends in the
     * wallet's id where a subgroup's ends in its room.
     */
    private fun verifyMember(certificate: Event, ancestorChatRoomId: String, ancestorThresholdPublicKey: HexKey): GuardedWallet? = runCatching {
        val verified = MemberWalletCertificateEvent.verify(certificate, certificate.pubKey) ?: return null
        val account = verified.account

        val lineage = GuardianDerivation.lineage(account.guardians, verified.walletId, ancestorChatRoomId) ?: return null
        val named = account.guardians.first { it.chatRoomId.equals(ancestorChatRoomId, ignoreCase = true) }
        val walked = GuardianDerivation.root(ancestorThresholdPublicKey).extendedPublicKey
            .derivePublicKey(lineage.map(GuardianDerivation::childIndex))
        if (walked.publicKey != named.publicKey || walked.chaincode != named.chainCode) return null

        GuardedWallet(
            ancestorChatRoomId = ancestorChatRoomId,
            descendantChatRoomId = verified.walletId,
            statement = certificate,
            account = account,
            certificate = certificate,
            lineage = lineage,
            subject = GuardedSubject.Member(verified.walletId, verified.memberPublicKey, verified.label),
        )
    }.getOrNull()

    /**
     * [wallet] with its member's profile name, and the names of the group that
     * directly guards it and of the branch it sits under, where this device knows
     * them. [known] are birth certificates that came to hand some other way --
     * those the statements told to the holding room carry.
     */
    private suspend fun named(database: BennuDatabase, wallet: GuardedWallet, known: List<Event> = emptyList()): GuardedWallet {
        val member = wallet.subject as? GuardedSubject.Member ?: return wallet
        val name = database.profileDao().getProfileByPublicKey(member.ownerPublicKey)?.humanReadableNameOrPubkey()
        val guardians = wallet.account.guardians

        suspend fun certificateOf(position: Int): Event? =
            guardians.getOrNull(position + 1)?.let { parent -> foundingCertificateOf(database, guardians[position].chatRoomId, parent.chatRoomId, known) }

        // Founding name first, then the room's name here: the order D2 of
        // docs/member-wallet-guarding-group.md gives, so the card agrees with a
        // subgroup row in the same list naming the same room.
        suspend fun nameOf(position: Int, certificate: Event?): String? =
            certificate?.let { SubgroupBirthCertificateEvent.parseName(it.tags) }
                ?: database.chatRoomDao().findChatRoomById(guardians[position].chatRoomId)?.chatRoom?.subject?.takeIf { it.isNotBlank() }

        val guardianCertificate = certificateOf(0)
        return wallet.copy(
            subject = member.copy(
                ownerName = name,
                guardianName = nameOf(0, guardianCertificate),
                branchName = wallet.position.takeIf { it > 0 }?.let { nameOf(it - 1, certificateOf(it - 1)) },
            ),
            guardianCertificate = guardianCertificate,
        )
    }

    /**
     * What [roomId] was called at its founding, read off a birth certificate
     * [parentRoomId] signed for it -- or null when this device holds none it can
     * check. Each candidate is checked with `SubgroupBirthCertificateEvent.certifies`,
     * whoever handed it over: [carried] first, then the certificates the parent
     * signed (every device in the parent holds them), then the one in [roomId]'s
     * own key state (every device in the room). `docs/member-wallet-guarding-group.md`, D3.
     */
    suspend fun foundingNameOf(
        database: BennuDatabase,
        roomId: String,
        parentRoomId: String,
        carried: List<Event> = emptyList(),
    ): String? = foundingCertificateOf(database, roomId, parentRoomId, carried)?.let { SubgroupBirthCertificateEvent.parseName(it.tags) }

    /** The birth certificate [foundingNameOf] reads the name off, checked, or null. */
    suspend fun foundingCertificateOf(
        database: BennuDatabase,
        roomId: String,
        parentRoomId: String,
        carried: List<Event> = emptyList(),
    ): Event? {
        fun List<Event>.certifying(): Event? = firstOrNull { SubgroupBirthCertificateEvent.certifies(it, roomId, parentRoomId) }

        carried.certifying()?.let { return it }
        database.groupSignedEventDao()
            .getByChatRoomIdAndKind(parentRoomId, SubgroupBirthCertificateEvent.KIND)
            .map { it.toEvent() }
            .certifying()?.let { return it }
        return listOfNotNull(
            database.groupKeyStateDao().getByChatRoomId(roomId)?.birthCertificateJson?.let { Event.fromJsonOrNull(it) }
        ).certifying()
    }

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

        // What it was told, and -- for its own members' wallets -- what it signed:
        // the group holds a member wallet certificate it signed, and needs no
        // notice to learn of it.
        val notices = database.marmotInnerEventDao()
            .getByChatRoomAndKinds(ancestorChatRoomId, listOf(GuardedWalletNoticeEvent.KIND))
        val told = notices.mapNotNull { row -> Event.fromJsonOrNull(row.content) }
        val signed = database.groupSignedEventDao()
            .getByChatRoomIdAndKind(ancestorChatRoomId, MemberWalletCertificateEvent.KIND)
            .map { it.toEvent() }

        // Birth certificates the room was handed: the one a subgroup's statement
        // carries, and the guardian's a member wallet's notice carries. Pooled,
        // since each is checked against the room and parent it is used for, and
        // one that certifies neither is never used.
        val known = told.filter { it.kind == GroupWalletStatementEvent.KIND }
            .mapNotNull { GroupWalletStatementEvent.read(it.tags, it.content)?.certificate } +
            notices.mapNotNull { GuardedWalletNoticeEvent.certificateIn(it.tags) }

        return (told + signed)
            .mapNotNull { verify(it, ancestorChatRoomId, thresholdPublicKey) }
            .map { named(database, it, known) }
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
        /** What the statement's room was certified as, for the screen. Null for a member's wallet. */
        val name: String?,
        val rooms: List<LocalChatRoom>,
        /** For a member's wallet: the member, and their profile name where this device knows one. */
        val ownerPublicKey: HexKey? = null,
        val ownerName: String? = null,
        /** For a member's wallet: its guardian's birth certificate, carried on the notice -- `GuardedWalletNoticeEvent`. */
        val certificate: Event? = null,
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
        // Everything it guards or was told of -- its own members' wallets among
        // them, since it holds those certificates itself -- passed on a
        // generation further.
        val passedOn = guardedWalletsOf(database, chatRoomId)
            .filter { it.chain == chain }

        val candidates = listOfNotNull(own?.let { (statement, name) -> Shareable(statement, name, emptyList()) }) +
            passedOn.map { wallet ->
                val member = wallet.subject as? GuardedSubject.Member
                Shareable(wallet.statement, wallet.name, emptyList(), member?.ownerPublicKey, member?.ownerName, wallet.guardianCertificate)
            }

        return candidates.mapNotNull { candidate ->
            val rooms = targetsFor(database, candidate.statement, from = chatRoomId, withCertificate = candidate.certificate != null)
            rooms.takeIf { it.isNotEmpty() }?.let { candidate.copy(rooms = it) }
        }
    }

    /** The guardians [statement] names, nearest first: a subgroup's statement's, or a member wallet certificate's. */
    private fun guardiansNamedIn(statement: Event): List<GuardianDerivation.Guardian> = when (statement.kind) {
        MemberWalletCertificateEvent.KIND -> GuardianTag.parseAll(statement.tags).orEmpty()
        else -> GroupWalletStatementEvent.read(statement.tags, statement.content)?.guardians.orEmpty()
    }

    /**
     * The guardian rooms of [statement] above [from] this device can post into
     * and has not told it yet. Only Marmot rooms: a guardian is a group with a
     * key, and a group with a key has an MLS room. Only those above: a guardian
     * nearer the wallet than [from] is below it, and either holds the statement
     * already or is told by its own members.
     */
    private suspend fun targetsFor(database: BennuDatabase, statement: Event, from: String, withCertificate: Boolean): List<LocalChatRoom> {
        val guardians = guardiansNamedIn(statement).map { it.chatRoomId }
        val here = guardians.indexOfFirst { it.equals(from, ignoreCase = true) }
        return guardians.drop(here + 1)
            .mapNotNull { database.chatRoomDao().findChatRoomById(it) }
            .filter { it.chatRoom.mlsGroupState != null }
            .filterNot { room -> alreadyTold(database, room.chatRoom.id, statement.id, withCertificate) }
    }

    /**
     * Whether [chatRoomId] holds a notice of [statementId] -- one carrying the
     * guardian's certificate, when [withCertificate] says this device has one to
     * add. A room told before notices carried it can be told again, once, so the
     * name reaches it. `docs/member-wallet-guarding-group.md`, D5.
     */
    private suspend fun alreadyTold(database: BennuDatabase, chatRoomId: String, statementId: HexKey, withCertificate: Boolean): Boolean =
        database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(GuardedWalletNoticeEvent.KIND))
            .any { Event.fromJsonOrNull(it.content)?.id == statementId && (!withCertificate || GuardedWalletNoticeEvent.certificateIn(it.tags) != null) }

    /**
     * Tells every room in [rooms] about [statement], as [userPublicKey]: one
     * notice each, queued for the outbound pipeline the way every inner event
     * is. Returns how many were queued.
     *
     * Each notice names its room in an `h` tag. Without it, one statement told
     * to a grandchild's parent and grandparent in the same second is one id --
     * and one row, the last room's -- so the parent was never told.
     *
     * [certificate], for a member's wallet, is its guardian's birth certificate,
     * carried as a tag -- `GuardedWalletNoticeEvent`.
     */
    suspend fun share(
        database: BennuDatabase,
        statement: Event,
        rooms: List<LocalChatRoom>,
        userPublicKey: HexKey,
        createdAt: Long = Clock.System.now().epochSeconds,
        certificate: Event? = null,
    ): Int {
        val content = statement.toJson()

        rooms.forEach { room ->
            val tags = listOfNotNull(arrayOf("h", room.chatRoom.id), certificate?.let(GuardedWalletNoticeEvent::certificateTag)).toTypedArray()
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

    /** Coins of a guarded wallet one of its guardians took, in one transaction. */
    data class Taken(
        val txid: TxId,
        val guardian: GuardianDerivation.Guardian,
        val amount: Satoshi,
    )

    /**
     * Which guardians of [account] took which of [coins] in [spending] -- one
     * entry per guardian -- read off the witnesses alone.
     *
     * A guardian's input carries three things: a signature, a leaf, and the
     * control block proving the leaf is in the address. The leaf is matched
     * against the guardian leaves of the address the coin was at, so a leaf is
     * named only by a guardian whose key it is; anything else -- the wallet's own
     * key-path spend, a witness of another shape -- names nobody.
     */
    fun takenIn(
        spending: Transaction,
        account: GroupWalletDerivation.Account,
        coins: Map<OutPoint, Pair<TxOut, GroupWalletDerivation.Derived>>,
    ): List<Taken> {
        if (!account.isGuarded) return emptyList()

        return spending.txIn.mapNotNull { input ->
            val (txOut, derivation) = coins[input.outPoint] ?: return@mapNotNull null
            val stack = input.witness.stack.takeIf { it.size == 3 } ?: return@mapNotNull null
            val leaves = GuardianDerivation.leaves(account.guardians, account.chain, account.account, derivation.change, derivation.index)
            val position = leaves.indexOfFirst { it.script == stack[1] }.takeIf { it >= 0 } ?: return@mapNotNull null
            account.guardians[position] to txOut.amount
        }
            .groupBy({ it.first }, { it.second })
            .map { (guardian, amounts) -> Taken(spending.txid, guardian, amounts.sum()) }
    }

    /**
     * Writes a transcript line in [chatRoomId] for each of [taken] it has no line
     * for yet. Returns how many it wrote.
     *
     * The whole of the child's notice that an ancestor spent from its wallet --
     * there is no channel from the ancestor's room into this one -- so it is
     * written by whichever device reads the chain first, and every device that
     * reads it afterwards finds it already there.
     */
    suspend fun recordTaken(database: BennuDatabase, chatRoomId: String, taken: List<Taken>): Int {
        val written = database.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId)
            .map { it.chatMessage }
            .filter { it.messageType == ChatMessage.TYPE_GUARDIAN_SPEND }
            .map { it.content }

        val fresh = taken.filter { spend -> written.none { it.contains(spend.txid.toString()) } }
        fresh.forEach { spend ->
            val name = database.chatRoomDao().findChatRoomById(spend.guardian.chatRoomId)?.chatRoom?.subject
                ?.takeIf { it.isNotBlank() }
                ?: "A group above this one"
            database.chatMessageDao().upsert(
                ChatMessage(
                    senderPublicKey = spend.guardian.chatRoomId,
                    isUserMessage = false,
                    giftWrapPayloadId = null,
                    marmotGroupEventId = null,
                    marmotInnerEventId = null,
                    chatRoomId = chatRoomId,
                    content = "$name took ${spend.amount.sat} sats from this wallet through its guardian leaf, " +
                        "in transaction ${spend.txid}.",
                    messageType = ChatMessage.TYPE_GUARDIAN_SPEND,
                )
            )
            logger.i("${spend.guardian.chatRoomId} took ${spend.amount.sat} sats from $chatRoomId's guarded wallet in ${spend.txid}")
        }
        return fresh.size
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

        return when (val subject = named(database, wallet).subject) {
            is GuardedSubject.Member -> "Shared @${subject.ownerName ?: subject.ownerPublicKey.take(8)}'s guarded wallet with this group."
            is GuardedSubject.Subgroup -> subject.name?.let { "Shared the guarded wallet of $it with this group." }
                ?: "Shared a subgroup's guarded wallet with this group."
        }
    }
}
