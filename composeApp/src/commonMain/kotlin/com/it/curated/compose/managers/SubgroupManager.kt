package com.it.curated.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.database.CuratedDatabase
import com.it.curated.compose.database.model.DkgSession
import com.it.curated.compose.database.model.FrostSigningSession
import com.it.curated.compose.database.model.GroupKeyState
import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.database.model.types.DkgRitualStage
import com.it.curated.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.it.curated.compose.nostr.subgroup.SubgroupParentage

/**
 * A group making another group, and the four steps that take.
 *
 * ```
 *  1. ChillDKG among the child's admins  -> threshold key K, and so C = marmotGroupId(K)
 *  2. birth certificate, in the parent   -> the parent's quorum signs C
 *  3. key state, among the child's admins -> the child's quorum signs "C signs with K", carrying it
 *  4. the Marmot room                    -> somebody creates C and welcomes the admins
 * ```
 *
 * **All four happen in the parent's room**, bar the last, which makes a room of
 * its own. Steps 1 and 3 are held there because the child has nowhere else to be:
 * its id is derived from the key step 1 produces, so it cannot exist until
 * afterwards. Step 2 is there because the signature is the parent's.
 *
 * None of that order is a policy choice. Step 2 needs `C`, which does not exist
 * until step 1 produces `K`; step 3 carries the certificate, so it needs step 2;
 * and step 4 is gated on the key state for the same reason
 * `DkgRitualViewModel.createAdminGroup` already is -- a room created before its
 * group has agreed what it signs with is a room whose founding fact is settled
 * after the founding.
 *
 * This orchestrates; it does not reimplement. Step 1 is `ChillDkgRitualManager`,
 * steps 2 and 3 are `FrostSigningManager` sessions, and step 4 is
 * `MarmotGroupCreation`. What lives here is the order, the guards, and the
 * reading of what the parent has signed.
 *
 * ### Only step 1 belongs to the coordinator
 *
 * After the ceremony fixes the participant set, every remaining step is open to
 * somebody else: the certificate to any parent admin holding a share of the
 * parent's key, and the key state and the room to any of the child's admins, who
 * by then all hold shares of `K` and know `C`. A coordinator whose phone dies
 * after the ceremony strands nothing. That is why every function here reads its
 * state off stored rows rather than off a session object handed down from the
 * screen that started.
 *
 * See `docs/subgroups.md`.
 */
object SubgroupManager {
    private const val TAG = "SubgroupManager"

    private val logger = Logger.withTag(TAG)

    /**
     * The fewest admins a subgroup can be run by, the coordinator included.
     *
     * The same floor [com.it.curated.compose.database.model.types.ChatRoomType.MINIMUM_ROBUST_GROUP_SIZE]
     * states and for the same reason: below three a quorum is not a check, since
     * two admins means every decision needs both of them and one means the
     * coordinator is deciding alone.
     *
     * The coordinator counts. They are a ChillDKG participant by construction and
     * hold a share whether or not anybody ticked their name, so a picker that
     * asked for three *others* would be quietly building a group of four.
     */
    const val MINIMUM_ADMINS: Int = 3

    /**
     * Why these admins cannot hold a ceremony together, or null when they can.
     *
     * Read by the picker so a subgroup that could only fail is never offered, and
     * again before anything is published, because a screen not drawing something
     * is not a guard.
     *
     * The message is the user's, so each one says what to do rather than what is
     * wrong.
     */
    suspend fun refuseSubgroup(
        database: CuratedDatabase,
        parentRoom: LocalChatRoom,
        adminPublicKeys: Set<HexKey>,
        coordinatorPublicKey: HexKey
    ): String? {
        val admins = adminPublicKeys + coordinatorPublicKey
        val parentChatRoomId = parentRoom.chatRoom.id

        // A subgroup is certified by the parent's key, so a device holding no
        // share of it cannot open the session at all --
        // `FrostSigningManager.proposeSigningBatch` throws outright, and throwing
        // at a button is not a UI. Checked before the size rules because it is the
        // one refusal the user cannot fix by picking differently.
        if (!FrostSigningManager.canSign(database, parentChatRoomId)) {
            return "Only a member who holds a share of this group's key can make a subgroup."
        }

        // A non-admin proposing the group's signature is a proposal the admins
        // have to decline by hand, which is a worse outcome than not offering it.
        //
        // Read off the room's own `MarmotGroupData`, not off `Participant.adminAt`.
        // That column is written in exactly one place -- `processGroupMembershipChanges`,
        // on an arriving commit -- so on the device that *created* a room it is
        // null for everybody, including the creator, until some other member
        // sends a commit. A freshly made room has had no commits, so the flag
        // says nobody administers a group whose epoch-0 context names three
        // admins.
        //
        // The group data is the thing that column is a cache of: MIP-01 stamps
        // `admin_pubkeys` into the epoch-0 group context, every member gets it in
        // their Welcome, and `processGroupMembershipChanges` reads exactly this
        // when it writes the flag. Reading it directly cannot drift from what the
        // group agreed, and is right on both sides from the first moment.
        // Named for the parent, because `adminPublicKeys` in this function is the
        // *subgroup's* picked admins and the two must never be confused -- the
        // ceremony is opened over the second while running in a room governed by
        // the first.
        val parentAdminPublicKeys = runCatching {
            parentRoom.chatRoom.toMlsGroup()?.currentMarmotData()?.adminPubkeys
        }.getOrNull()

        if (parentAdminPublicKeys == null) {
            // No MLS state, or state this build cannot read. A NIP-17 room has no
            // admin list to consult and no group context to put a subgroup's
            // certificate in, which is the same reason the button is hidden there.
            return "A subgroup can only be made from a group that has admins."
        }

        if (!parentAdminPublicKeys.contains(coordinatorPublicKey)) {
            return "Only an admin of this group can make a subgroup."
        }

        if (admins.size < MINIMUM_ADMINS) {
            return "A subgroup needs at least $MINIMUM_ADMINS admins. Pick at least " +
                "${MINIMUM_ADMINS - 1} more people."
        }

        val members = ChillDkgRitualManager.memberPublicKeys(parentRoom)
        if (!members.containsAll(admins)) {
            return "Everyone in a subgroup has to be in this group first."
        }

        // A subgroup *may* be the whole group. It is a logical division -- a group
        // deciding that some of its work belongs to a differently-keyed room --
        // rather than a smaller membership, so "everybody" is a normal answer and
        // never this function's business to refuse.
        //
        // The only thing left to refuse is a ceremony already under way for the
        // same subgroup, and it is refused because a person cannot answer for two
        // of them: every admin would be asked twice, on two ladders, for two keys,
        // one of which nobody will ever make a room from.
        //
        // A *finished* one is not refused. It means that subgroup was made, and a
        // group is entitled to a second run by the same people -- which was
        // impossible while the ceremony ran in a room derived from its admins,
        // because asking again handed back the first ceremony's key and the "new"
        // subgroup was the old one renamed. Moving the ceremony into the parent's
        // room is what retired that, and this is the whole of what is left.
        val running = database.dkgSessionDao().getLatestSubgroupSessionFor(
            chatRoomId = parentChatRoomId,
            parentChatRoomId = parentChatRoomId,
            participantPublicKeys = DkgSession.formatParticipants(admins).orEmpty()
        )
        if (running != null &&
            running.stage != DkgRitualStage.FAILED &&
            running.stage != DkgRitualStage.COMPLETE
        ) {
            return "A key ceremony for a subgroup run by exactly these members is " +
                "already under way."
        }

        return null
    }

    /**
     * Step 2: asks the parent's quorum to certify the child.
     *
     * Runs in the **parent's** room, so the signature is by the parent's key and
     * the author is the parent's own room id -- which is what makes a certificate
     * checkable by anyone holding that id and nothing else.
     *
     * [key] is the *child's* completed ceremony, and it is read for what it
     * produced rather than handed to the session: the parent signs with the
     * parent's key, resolved from the parent's room the way every other signature
     * there is. A caller naming a ceremony here would be a caller choosing what
     * the group signs as.
     *
     * A session of one, never batched, for the reason `GroupKeyStateManager.propose`
     * gives: a batch is only as available as its worst item, and everything after
     * this depends on it.
     */
    suspend fun proposeBirthCertificate(
        database: CuratedDatabase,
        parentRoom: LocalChatRoom,
        userPublicKey: HexKey,
        key: DkgSession,
        adminPublicKeys: List<HexKey>,
        name: String,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    ): FrostSigningSession {
        val thresholdPublicKey = key.thresholdPublicKey
            ?: throw IllegalStateException(
                "Ceremony ${key.id} has produced no key for a subgroup to be certified on"
            )

        val subgroupChatRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path)
        val parentChatRoomId = parentRoom.chatRoom.id

        require(name.isNotBlank()) { "A subgroup has to be called something" }

        // The certificate is a signature over the name, so it has to be the name
        // the room ends up with. `MarmotGroupCreation` normalises again on the
        // way in and `MarmotGroupName.of` is idempotent, so the two agree by
        // construction rather than by both callers remembering.
        val subgroupName = MarmotGroupName.of(name)

        // One certificate per child. A second is a `d`-tag replacement of the
        // first rather than a second subgroup, and spending a quorum's attention
        // to restate something they have already signed is worse than doing
        // nothing. Two coordinators racing still produce two -- neither can see
        // the other's session before it completes -- and `certificateFor` folds
        // those together; this only stops the case somebody can actually see.
        check(certificateFor(database, subgroupChatRoomId, parentChatRoomId) == null) {
            "$parentChatRoomId has already certified $subgroupChatRoomId"
        }

        logger.i("Asking $parentChatRoomId to certify $subgroupChatRoomId as its subgroup")

        return FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = parentRoom,
            userPublicKey = userPublicKey,
            kind = SubgroupBirthCertificateEvent.KIND,
            tags = SubgroupBirthCertificateEvent.assembleTags(
                subgroupChatRoomId = subgroupChatRoomId,
                parentChatRoomId = parentChatRoomId,
                thresholdPublicKey = thresholdPublicKey,
                adminPublicKeys = adminPublicKeys,
                name = subgroupName,
                path = path
            ),
            content = subgroupChatRoomId
        )
    }

    /**
     * The newest certificate by [parentChatRoomId] that actually certifies
     * [subgroupChatRoomId], or null if this device holds none.
     *
     * **Two certificates for one child is a normal outcome, not a conflict.** Two
     * parent admins can press the button on the same admin set: the second's
     * `proposeRitual` folds into the ceremony the first opened, but both may go on
     * to propose a certificate and both sessions can complete.
     * `GroupSignedEvent` is keyed on the event id, so the rows coexist -- and
     * because both say the same true thing about the same child, which one wins
     * does not matter. The `d` tag makes them replacements of each other rather
     * than an accumulation, which is what it is for.
     */
    suspend fun certificateFor(
        database: CuratedDatabase,
        subgroupChatRoomId: String,
        parentChatRoomId: String
    ): GroupSignedEvent? =
        database.groupSignedEventDao()
            .getByChatRoomIdAndKind(parentChatRoomId, SubgroupBirthCertificateEvent.KIND)
            .filter {
                SubgroupBirthCertificateEvent.certifies(
                    event = it.toEvent(),
                    subgroupChatRoomId = subgroupChatRoomId,
                    parentChatRoomId = parentChatRoomId
                )
            }
            .maxByOrNull { it.createdAt }

    /**
     * Step 3: asks the child's quorum to sign its key state, carrying the
     * certificate.
     *
     * Runs where the ceremony ran -- the parent's room -- because it has to:
     * `GroupKeyStateManager.propose` and `FrostSigningManager.signingPath` both tie
     * a key state to the room its ceremony was held in, and the room the state is
     * *about* does not exist yet.
     *
     * Signed by the **child's** quorum for all that, not the parent's. The room is
     * only the transport; who signs comes from the ceremony's host keys, and the
     * key it signs with is the one that ceremony produced. A parent admin outside
     * the subgroup reads the messages and holds no share to answer them with.
     *
     * Refuses without a certificate that passes `certifies`. Every device that
     * receives the state runs the same check and drops it when it fails, so
     * proposing one this device would not believe spends a quorum's attention on
     * a statement nobody will keep.
     */
    suspend fun proposeSubgroupKeyState(
        database: CuratedDatabase,
        ceremonyRoom: LocalChatRoom,
        userPublicKey: HexKey,
        key: DkgSession,
        parentChatRoomId: HexKey,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    ): FrostSigningSession {
        val thresholdPublicKey = key.thresholdPublicKey
            ?: throw IllegalStateException("Ceremony ${key.id} has produced no key to sign a state for")

        val subgroupChatRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path)

        val certificate = certificateFor(database, subgroupChatRoomId, parentChatRoomId)
            ?: throw IllegalStateException(
                "$parentChatRoomId has not certified $subgroupChatRoomId, so its key state " +
                    "cannot name a parent yet"
            )

        return GroupKeyStateManager.propose(
            database = database,
            localChatRoom = ceremonyRoom,
            userPublicKey = userPublicKey,
            key = key,
            path = path,
            parent = SubgroupParentage(
                parentChatRoomId = parentChatRoomId,
                certificate = certificate.toEvent()
            )
        )
    }

    /**
     * A subgroup as the parent's own record has it, plus whatever this device
     * happens to hold about it.
     *
     * [certificate] is the only part that is evidence. [name] and [adminPublicKeys]
     * come off it too and are the **founding** roster -- what the parent approved,
     * not who is in the room now -- so a screen showing them must say so or prefer
     * [room] where there is one.
     */
    data class Subgroup(
        val chatRoomId: String,
        val parentChatRoomId: String,
        val certificate: GroupSignedEvent,
        /** What the parent certified it as. Frozen; a rename does not reach it. */
        val name: String?,
        /** Who the parent certified it for. Frozen; joins and leaves do not reach it. */
        val adminPublicKeys: List<HexKey>,
        /** The room, when this device has one -- which is the live answer to both. */
        val room: LocalChatRoom?,
        /** The child's key state, when this device holds one. */
        val keyState: GroupKeyState?,
    ) {
        /** Whether this device can open the subgroup, rather than only know of it. */
        val isJoined: Boolean get() = room != null
    }

    /**
     * Every subgroup [parentChatRoomId] has certified, as far as this device knows.
     *
     * Read off the parent's signed certificates rather than off a table of
     * subgroups. The certificates *are* the record: they are the group's own
     * signed statement, they are checkable without a lookup, and every device in
     * the parent room already has them -- `FrostSigningManager.complete` files a
     * `GroupSignedEvent` on every device that followed the session, not only on
     * the signers'. A table beside them would be a second copy that can disagree.
     *
     * Includes children whose room this device does not have, which is the normal
     * case for a parent member who is not in the subgroup, and children whose room
     * nobody has created yet, which is the normal case between steps 2 and 4.
     *
     * Newest first, and one entry per child: a second certificate for the same
     * child replaces the first rather than standing beside it.
     */
    suspend fun subgroupsOf(
        database: CuratedDatabase,
        parentChatRoomId: String
    ): List<Subgroup> =
        database.groupSignedEventDao()
            .getByChatRoomIdAndKind(parentChatRoomId, SubgroupBirthCertificateEvent.KIND)
            .mapNotNull { signed ->
                val event = signed.toEvent()
                val chatRoomId = SubgroupBirthCertificateEvent.parseSubgroupChatRoomId(event.tags)
                    ?: return@mapNotNull null

                if (!SubgroupBirthCertificateEvent.certifies(event, chatRoomId, parentChatRoomId)) {
                    return@mapNotNull null
                }

                signed to chatRoomId
            }
            .groupBy { (_, chatRoomId) -> chatRoomId }
            .mapNotNull { (chatRoomId, certificates) ->
                val newest = certificates.maxByOrNull { (signed, _) -> signed.createdAt }
                    ?: return@mapNotNull null
                val (signed, _) = newest
                val event = signed.toEvent()

                Subgroup(
                    chatRoomId = chatRoomId,
                    parentChatRoomId = parentChatRoomId,
                    certificate = signed,
                    name = SubgroupBirthCertificateEvent.parseName(event.tags),
                    adminPublicKeys = SubgroupBirthCertificateEvent.parseAdminPublicKeys(event.tags),
                    room = database.chatRoomDao().findChatRoomById(chatRoomId),
                    keyState = database.groupKeyStateDao().getByChatRoomId(chatRoomId),
                )
            }
            .sortedByDescending { it.certificate.createdAt }

    /**
     * The group a room is a subgroup of, verified, or null if it is nobody's
     * child.
     *
     * Off the `GroupKeyState`, which only carries a parent whose certificate was
     * checked -- see `GroupKeyStateManager.stateFrom`. A room whose state this
     * device does not hold answers null even when it really is a subgroup, which
     * is the position a member welcomed in after the founding is in; the
     * unverified hint in the room's own description is the most they get, and it
     * must never be written here.
     */
    suspend fun parentOf(database: CuratedDatabase, chatRoomId: String): HexKey? =
        database.groupKeyStateDao().getByChatRoomId(chatRoomId)?.parentChatRoomId

    /**
     * Files a certificate that arrived, or drops it and says why.
     *
     * Reached from two directions and it cannot tell them apart, which is why it
     * checks. `FrostSigningManager.applySignedEvent` calls it with an event whose
     * signature has already been verified against the session's key; the inbound
     * Marmot path calls it with whatever a member put in the room. A member can
     * send a rumor of this kind, so a certificate is believed here on its own
     * merits or not at all.
     *
     * Nothing is written that is not already on file: the `GroupSignedEvent` row
     * is `recordSignedEvents`' to write, and the parent link on the child's room
     * comes off the child's own key state. What this adds is the check and the
     * refusal.
     */
    suspend fun record(
        database: CuratedDatabase,
        chatRoomId: String,
        innerEvent: Event
    ): GroupSignedEvent? {
        val subgroupChatRoomId =
            SubgroupBirthCertificateEvent.parseSubgroupChatRoomId(innerEvent.tags)
        if (subgroupChatRoomId == null) {
            logger.w("A birth certificate in $chatRoomId names no subgroup; dropping")
            return null
        }

        if (!SubgroupBirthCertificateEvent.certifies(innerEvent, subgroupChatRoomId, chatRoomId)) {
            logger.w(
                "A birth certificate in $chatRoomId for $subgroupChatRoomId carries no " +
                    "signature by $chatRoomId; dropping"
            )
            return null
        }

        logger.i("$chatRoomId has certified $subgroupChatRoomId as its subgroup")

        return database.groupSignedEventDao().getById(innerEvent.id)
    }
}
