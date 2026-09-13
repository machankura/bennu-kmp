package to.curare.compose.database.dao

import androidx.room3.Dao
import androidx.room3.Transaction
import to.curare.compose.database.CurareDatabase
import to.curare.compose.managers.ChronicleManager
import to.curare.compose.database.model.BroadcastNostrEventRequest
import to.curare.compose.database.model.ChatMessage
import to.curare.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation
import to.curare.compose.database.model.ChatMessageNostrEventRelation
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GiftWrapPayload
import to.curare.compose.database.model.MarmotCommitResult
import to.curare.compose.database.model.MarmotGroupEvent
import to.curare.compose.database.model.MarmotInnerEvent
import to.curare.compose.database.model.MarmotInnerEvent.Companion.disappearingExpiration
import to.curare.compose.database.model.MarmotKeyPackage
import to.curare.compose.database.model.MarmotRetainedEpochSecret
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Participant
import to.curare.compose.exceptions.MarmotMissingChatGroupException
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.exporterSecret
import to.curare.compose.extensions.shortened
import to.curare.compose.extensions.toHex
import to.curare.compose.managers.MarmotInboundManager.EPOCH_RETENTION_WINDOW
import to.curare.compose.nostr.MarmotDelivery
import to.curare.compose.nostr.MarmotDirectMessage
import to.curare.compose.nostr.Relays
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mip01Groups.MarmotGroupData
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEventEncryption
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip40Expiration.expiration
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.utils.RandomInstance
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

@Dao
abstract class MarmotOutboundDao(
    private val database: CurareDatabase
) {
    val logger = Logger.withTag(TAG)

    @Transaction
    open suspend fun createMlsDirectMessageChatRoom(
        name: String? = null,
        description: String? = null,
        userPublicKey: HexKey,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage,
        relays: List<String> = Relays.DefaultDMRelayList.map { it.url }
    ): String {
        val nostrGroupId = RandomInstance.bytes(32).toHexKey()
        val mlsGroup = MlsGroup.create(
            identity = userPublicKey.hexToByteArray(),
            initialExtensions = listOf(
                MarmotGroupData.bootstrap(
                    nostrGroupId = nostrGroupId,
                    creatorPubKey = userPublicKey,
                    outboxRelays = relays,
                ).toExtension()
            )
        )

        // Save Chat Room
        //
        // Member since the group existed, because this device is what created it:
        // there is no epoch of this group that predates us, and so nothing in it
        // for ChatRoom.predatesMembership to hold back.
        val createdAt = Clock.System.now()
        val chatRoom = ChatRoom(
            id = nostrGroupId,
            userPublicKey = userPublicKey,
            mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
            subject = name,
            description = description,
            joinedGroupAt = createdAt,
            createdAt = createdAt,
        )
        database.chatRoomDao().upsert(
            chatRoom
        )

        // Save Participants...
        database.participantDao().upsert(
            listOf(
                Participant(
                    participantPublicKey = userPublicKey,
                    chatRoomId = chatRoom.id,
                    relayHint = relays.first()
                ),
                Participant(
                    participantPublicKey = peerPublicKey,
                    chatRoomId = chatRoom.id,
                    relayHint = relays.first()
                )
            )
        )

        // Invite peer...
        inviteMember(
            nostrGroupId = nostrGroupId,
            mlsGroup = mlsGroup,
            userPublicKey = userPublicKey,
            peerPublicKey = peerPublicKey,
            peerKeyPackage = peerKeyPackage,
            relays = relays
        )

        // Update chatRoom
        database.chatRoomDao().upsert(
            chatRoom.copy(
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex(),
            )
        )

        return nostrGroupId
    }

    @Transaction
    open suspend fun inviteMemberToChatRoom(
        localChatRoom: LocalChatRoom,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage,
    ) {
        // A room restored from an inbound gift wrap has no persisted MLS state, so there is
        // nothing to add a member to. Say so instead of silently doing nothing and letting the
        // caller report success.
        val mlsGroup = localChatRoom.chatRoom.toMlsGroup()
            ?: throw MarmotMissingChatGroupException(
                "No MLS group state for chat room ${localChatRoom.chatRoom.id}; cannot invite $peerPublicKey"
            )

        val relays = Relays.DefaultDMRelayList.map { it.url }

        // The invitee needs a Participant row before the Welcome is sealed: sealGiftWrapPayload
        // walks the participants of the room to decide who to wrap for, so without this the
        // Welcome produced no gift wraps at all and sat unsealed forever.
        database.participantDao().upsert(
            listOf(
                Participant(
                    participantPublicKey = peerPublicKey,
                    chatRoomId = localChatRoom.chatRoom.id,
                    relayHint = relays.first()
                )
            )
        )

        inviteMember(
            nostrGroupId = localChatRoom.chatRoom.id,
            mlsGroup = mlsGroup,
            userPublicKey = localChatRoom.chatRoom.userPublicKey,
            peerPublicKey = peerPublicKey,
            peerKeyPackage = peerKeyPackage,
            relays = relays
        )

        // `addMember` advanced the in-memory group to the next epoch. Without persisting it the
        // creator kept encrypting under the old epoch — which the new member cannot decrypt —
        // and the next invite would re-derive from stale state and produce a conflicting commit.
        database.chatRoomDao().upsert(
            localChatRoom.chatRoom.copy(
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex()
            )
        )
    }

    /**
     * Adds every member in [peers] to a group in a single MLS commit.
     *
     * The shape to use whenever the whole membership is known up front -- creating a
     * room, or opening the admin room after a key ceremony. One commit, one
     * Welcome carrying an EncryptedGroupSecrets per joiner, and crucially **no
     * intermediate epoch for anybody to miss**.
     *
     * Inviting one at a time creates an epoch per member, and each of those commits
     * races the previous member's Welcome. MarmotInboundManager refuses future-epoch
     * messages outright -- no queue, no replay -- so a member who loses that race is
     * silently stuck an epoch behind while the caller sees a successful invite.
     * Batching removes the race rather than narrowing it, because no member ever has
     * to process a commit for an epoch they were not yet in.
     *
     * Only batches when the group is still just its creator, which is exactly the
     * case this exists for. Adding a batch to a group that already has members would
     * take the deferred-welcome path, and MarmotCommitResult records one peer per
     * commit -- so that case falls back to inviting sequentially, which is correct
     * if not ideal. Batching there needs that model to hold a list first; see
     * docs/marmot-membership.md.
     *
     * @return the members that could not be added, in the order given.
     */
    @Transaction
    open suspend fun addMembersToChatRoom(
        localChatRoom: LocalChatRoom,
        peers: List<Pair<HexKey, MarmotKeyPackage>>
    ): List<HexKey> {
        if (peers.isEmpty()) return emptyList()

        val mlsGroup = localChatRoom.chatRoom.toMlsGroup()
            ?: throw MarmotMissingChatGroupException(
                "No MLS state for chat room ${localChatRoom.chatRoom.id}; cannot add members"
            )

        // A group that already has members cannot take this path -- see the kdoc.
        if (mlsGroup.members().size != 1) {
            val notAdded = mutableListOf<HexKey>()

            peers.forEach { (peerPublicKey, peerKeyPackage) ->
                // Re-read between invites: each advances the epoch and persists new
                // state, so a snapshot from before the previous one would build this
                // commit on state the group has already left.
                val current = database.chatRoomDao().findChatRoomById(localChatRoom.chatRoom.id)
                if (current == null) {
                    notAdded.add(peerPublicKey)
                    return@forEach
                }

                runCatching {
                    inviteMemberToChatRoom(current, peerPublicKey, peerKeyPackage)
                }.onFailure {
                    logger.e("Failed to invite $peerPublicKey to ${localChatRoom.chatRoom.id}", it)
                    notAdded.add(peerPublicKey)
                    announceInviteFailed(
                        chatRoomId = localChatRoom.chatRoom.id,
                        userPublicKey = localChatRoom.chatRoom.userPublicKey,
                        peerPublicKey = peerPublicKey,
                        reason = it.message,
                    )
                }
            }

            return notAdded
        }

        val relays = Relays.DefaultDMRelayList.map { it.url }

        // Participant rows must exist before any Welcome is sealed:
        // sealGiftWrapPayload walks the room's participants to decide who to wrap
        // for, so without these the Welcomes produce no gift wraps and sit unsealed.
        database.participantDao().upsert(
            peers.map { (peerPublicKey, _) ->
                Participant(
                    participantPublicKey = peerPublicKey,
                    chatRoomId = localChatRoom.chatRoom.id,
                    relayHint = relays.first()
                )
            }
        )

        peers.forEach { (peerPublicKey, peerKeyPackage) ->
            val keyPackage = KeyPackageUtils.decodeKeyPackage(peerKeyPackage.tlsEncodedMarmotKeyPackage)

            val credential = keyPackage.leafNode.credential
            require(credential is Credential.Basic) { "KeyPackage must use BasicCredential" }
            require(credential.identity.toHexKey() == peerPublicKey) {
                "KeyPackage credential identity does not match memberPubKey"
            }

            mlsGroup.proposeAdd(peerKeyPackage.tlsEncodedMarmotKeyPackage)
        }

        val retainedBefore = mlsGroup.retainedSecrets()
        val commitResult = mlsGroup.commit()

        database.marmotRetainedEpochSecretDao().insert(
            MarmotRetainedEpochSecret(
                chatRoomId = localChatRoom.chatRoom.id,
                epoch = retainedBefore.epoch,
                senderDataSecret = retainedBefore.senderDataSecret,
                encryptionSecret = retainedBefore.encryptionSecret,
                leafCount = retainedBefore.leafCount,
                exporterSecret = retainedBefore.exporterSecret,
            )
        )

        // `commit` advanced the in-memory group. Persist before delivering, so a
        // crash between the two leaves the group at the epoch the Welcomes describe
        // rather than one behind it.
        database.chatRoomDao().upsert(
            localChatRoom.chatRoom.copy(
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex()
            )
        )

        // One line each, as `inviteMember` writes for the invites it makes -- this
        // path does its own commit and never goes through it. Written before the
        // Welcomes rather than after, so a delivery that fails has an invite to be
        // read against instead of a failure on its own.
        peers.forEach { (peerPublicKey, _) ->
            announceMembership(
                chatRoomId = localChatRoom.chatRoom.id,
                userPublicKey = localChatRoom.chatRoom.userPublicKey,
                messageType = ChatMessage.TYPE_MEMBER_INVITED,
                content = "Invited ${memberName(peerPublicKey)} to the group",
            )
        }

        val welcomeBytes = commitResult.welcomeBytes
        if (welcomeBytes == null) {
            logger.e("Batched commit for ${localChatRoom.chatRoom.id} produced no welcome")
            peers.forEach { (peerPublicKey, _) ->
                announceInviteFailed(
                    chatRoomId = localChatRoom.chatRoom.id,
                    userPublicKey = localChatRoom.chatRoom.userPublicKey,
                    peerPublicKey = peerPublicKey,
                    reason = "the group produced no invitation to send",
                )
            }
            return peers.map { it.first }
        }

        // No commit is broadcast: the group was only its creator, so there is nobody
        // to inform. The same Welcome blob goes to every joiner -- each finds its own
        // EncryptedGroupSecrets entry by key package reference -- but delivery is per
        // peer, because each Welcome event is tagged with that peer's key package.
        val notAdded = mutableListOf<HexKey>()

        peers.forEach { (peerPublicKey, peerKeyPackage) ->
            // `deliveryWelcome` swallows what it catches and reports it as a
            // transcript line instead, so its answer is what says whether this peer
            // was reached -- a runCatching here would see nothing to catch.
            val delivered = deliveryWelcome(
                nostrGroupId = localChatRoom.chatRoom.id,
                userPublicKey = localChatRoom.chatRoom.userPublicKey,
                welcomeBytes = welcomeBytes,
                peerKeyPackageEventId = peerKeyPackage.id,
                relays = relays,
                createdAt = Clock.System.now()
            )

            if (!delivered) {
                logger.e("Failed to deliver the welcome to $peerPublicKey")
                notAdded.add(peerPublicKey)
            }
        }

        return notAdded
    }

    /**
     * One line in the room's transcript, about a membership change.
     *
     * The same shape as `ChronicleManager.announce`, and for the same reason: this
     * is the device saying what it just did, not an event anybody sent. Content is
     * a whole sentence, so nothing prefixes a name to it -- see
     * [ChatMessage.MEMBERSHIP_TYPES].
     */
    private suspend fun announceMembership(
        chatRoomId: HexKey,
        userPublicKey: HexKey,
        messageType: String,
        content: String,
    ) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content,
                messageType = messageType,
                chatRoomId = chatRoomId,
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
    }

    /**
     * What to call an invitee on a membership line.
     *
     * Written into the content rather than resolved at render time, because these
     * lines are about something that happened once and the AUTHORED-set machinery
     * that follows a rename is for lines with an actor. A member being invited has
     * a Profile row by construction -- Participant.participantPublicKey is a
     * foreign key onto it -- so the fallback is for the delivery path, which runs
     * long after the invite and reads its peer back out of a key package.
     */
    private suspend fun memberName(publicKey: HexKey): String =
        database.profileDao().getProfileByPublicKey(publicKey)?.humanReadableNameOrPubkey()
            ?: publicKey.shortened()

    /**
     * The Welcome for an invite made earlier has gone out.
     *
     * Written by the deferred delivery site only -- `DatabaseNostrRepository`, once
     * a relay has acknowledged the commit. An invite into a group that was still
     * just its creator sends its Welcome in the same breath as the invite, so its
     * [ChatMessage.TYPE_MEMBER_INVITED] line already says this and a second one
     * would only be the same second told twice. See [ChatMessage.MEMBERSHIP_TYPES].
     */
    suspend fun announceInviteSent(
        chatRoomId: HexKey,
        userPublicKey: HexKey,
        peerKeyPackageEventId: HexKey,
    ) {
        val peerPublicKey = database.marmotKeyPackageDao()
            .getMarmotKeyPackageById(peerKeyPackageEventId)?.publicKey

        announceMembership(
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_MEMBER_INVITE_SENT,
            content = peerPublicKey?.let { "Sent ${memberName(it)} their invitation" }
                ?: "Sent the invitation",
        )
    }

    /**
     * An invite did not make it, and nothing else will say so.
     *
     * The reason is put in the line because it is the only copy the user gets: the
     * screen that asked for the invite is gone by the time the Welcome is
     * delivered, and the failures that happen before it closes take the whole
     * transaction -- and the invite line inside it -- down with them. See
     * `DatabaseChatRepository.inviteMember`.
     */
    suspend fun announceInviteFailed(
        chatRoomId: HexKey,
        userPublicKey: HexKey,
        peerPublicKey: HexKey?,
        reason: String?,
    ) {
        val who = peerPublicKey?.let { "${memberName(it)}'s invitation" } ?: "the invitation"
        val why = reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""

        announceMembership(
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_MEMBER_INVITE_FAILED,
            content = "Couldn't send $who$why",
        )
    }

    private suspend fun inviteMember(
        nostrGroupId: HexKey,
        mlsGroup: MlsGroup,
        userPublicKey: HexKey,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage,
        relays: List<String> = Relays.DefaultDMRelayList.map { it.url },
    ) {
        logger.d("inviteMember: $peerPublicKey")

        // Read before `addMember` advances the tree. A group holding only its
        // creator has nobody to tell about the commit -- no other member exists, and
        // nobody outside the group can decrypt it -- so broadcasting one is noise,
        // and deferring the welcome until a relay acknowledges that noise is how the
        // first invitee ends up never hearing anything at all.
        //
        // Derived here rather than passed in because no caller is in a better
        // position to know it, and both of the ones that tried got it wrong:
        // inviteMemberToChatRoom hardcoded false, so every group's first invite took
        // the deferred path. `members()` skips empty leaves, so this stays right for
        // a group that has had members removed.
        //
        // True exactly once per group, whatever its final size: see
        // docs/marmot-membership.md.
        val isOneMemberInitialGroupCreation = mlsGroup.members().size == 1

        // Through the sniffing decoder rather than MlsKeyPackage.decodeTls. The
        // ingest stores every KeyPackage bare, so both read the same bytes today;
        // this one also reads a framed row -- the current profile's published
        // shape -- should one ever be stored that way, rather than taking the
        // frame's version and wire-format words for the package's own.
        val keyPackage = KeyPackageUtils.decodeKeyPackage(
            peerKeyPackage.tlsEncodedMarmotKeyPackage
        )

        val credential = keyPackage.leafNode.credential
        require(credential is Credential.Basic) {
            "KeyPackage must use BasicCredential"
        }
        require(credential.identity.toHexKey() == peerPublicKey) {
            "KeyPackage credential identity does not match memberPubKey"
        }

        // Say so now, not when the Welcome eventually goes out. On the deferred path
        // that is a relay round trip away and may never happen at all, and until this
        // line existed the room showed nothing whatsoever in the meantime -- an invite
        // that was queued, one that failed to reach the wire and one that was never
        // made all looked identical from the transcript. Inside the caller's
        // transaction, so an invite that does not survive `addMember` leaves no claim
        // that it did.
        announceMembership(
            chatRoomId = nostrGroupId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_MEMBER_INVITED,
            content = "Invited ${memberName(peerPublicKey)} to the group",
        )

        val retainedBefore = mlsGroup.retainedSecrets()
        val commitResult = mlsGroup.addMember(
            peerKeyPackage.tlsEncodedMarmotKeyPackage
        )

        database.marmotRetainedEpochSecretDao().insert(
            MarmotRetainedEpochSecret(
                chatRoomId = nostrGroupId,
                epoch = retainedBefore.epoch,
                senderDataSecret = retainedBefore.senderDataSecret,
                encryptionSecret = retainedBefore.encryptionSecret,
                leafCount = retainedBefore.leafCount,
                exporterSecret = retainedBefore.exporterSecret,
            )
        )

        // Trim retained epochs out of the retention window...
        val defenestratableMarmotRetainedEpochSecrets = database.marmotRetainedEpochSecretDao()
            .getDefenestratableMarmotRetainedEpochSecretForChatRoomId(
                chatRoomId = nostrGroupId,
                epochCutOffPoint = retainedBefore.epoch - EPOCH_RETENTION_WINDOW.toLong()
            )
        logger.d("Defenestratable Retained Epochs: ${defenestratableMarmotRetainedEpochSecrets.map { it.epoch }}")
        database.marmotRetainedEpochSecretDao().defenestrate(
            defenestratableMarmotRetainedEpochSecrets
        )

        if (isOneMemberInitialGroupCreation) {
            // Send welcome event to participant
            commitResult.welcomeBytes?.let { welcomeBytes ->
                deliveryWelcome(
                    nostrGroupId = nostrGroupId,
                    userPublicKey = userPublicKey,
                    welcomeBytes = welcomeBytes,
                    peerKeyPackageEventId = peerKeyPackage.id,
                    relays = relays,
                    createdAt = Clock.System.now()
                )
            }
        } else {
            // CommitEvent...
            val encryptedContent = encryptedCommitEvent(
                mlsGroup,
                commitBytes = commitResult.framedCommitBytes,
                exporterKey = commitResult.preCommitExporterSecret
            )

            val template = GroupEvent.build(
                encryptedContentBase64 = encryptedContent,
                nostrGroupId = nostrGroupId
            )

            val ephemeralSigner = NostrSignerInternal(KeyPair())
            val commitEvent: GroupEvent = ephemeralSigner.sign(template)

            database.nostrEventDao().upsert(
                NostrEvent(
                    id = commitEvent.id,
                    createdAt = Instant.fromEpochSeconds(commitEvent.createdAt),
                    content = commitEvent.content,
                    sig = commitEvent.sig,
                    tags = commitEvent.tags,
                    kind = commitEvent.kind,
                    pubKey = commitEvent.pubKey
                )
            )

            database.marmotGroupEventDao().upsert(
                MarmotGroupEvent(
                    id = commitEvent.id,
                    createdAt = Instant.fromEpochSeconds(commitEvent.createdAt),
                    encryptedContent = commitEvent.encryptedContent(),
                    publicKey = commitEvent.pubKey,
                    userPublicKey = userPublicKey,
                    expiresAt = commitEvent.expiration()?.let { Instant.fromEpochSeconds(it) },
                    chatRoomId = nostrGroupId,
                    signature = commitEvent.sig
                )
            )

            // Save commitResult... in case we need to broadcast welcomeEvent after relay acknowledgement...
            database.marmotCommitResultDao().upsert(
                MarmotCommitResult.from(
                    commitEventId = commitEvent.id,
                    commitResult = commitResult,
                    chatRoomId = nostrGroupId,
                    userPublicKey = userPublicKey,
                    peerKeyPackageEventId = peerKeyPackage.id,
                    isOneMemberInitialGroupCreation = isOneMemberInitialGroupCreation,
                    createdAt = Instant.fromEpochSeconds(commitEvent.createdAt)
                )
            )

            // Broadcast commitment so other members get it...
            database.broadcastNostrEventRequestDao().insert(
                relays.map {
                    BroadcastNostrEventRequest(
                        nostrEventId = commitEvent.id,
                        relayURL = it
                    )
                }
            )
        }
    }

    /**
     * Put the invitee's Welcome on the outbound queue.
     *
     * Returns whether it got there. The caller cannot see that any other way -- the
     * body swallows what it catches, deliberately, because on the deferred path this
     * runs from a relay acknowledgement with no invite screen left to fail back to.
     * What replaced reporting nothing at all is the [ChatMessage.TYPE_MEMBER_INVITE_FAILED]
     * line written below, which outlives the screen and is the only account of a
     * Welcome that never went out.
     */
    suspend fun deliveryWelcome(
        nostrGroupId: HexKey,
        userPublicKey: HexKey,
        welcomeBytes: ByteArray,
        peerKeyPackageEventId: HexKey,
        relays: List<String>,
        createdAt: Instant
    ): Boolean {
        try {
            val welcomeBase64 = Base64.encode(
                source = welcomeBytes
            )

            // GiftWrap...
            val welcomeEvent = WelcomeEvent.build(
                welcomeBase64 = welcomeBase64,
                createdAt = createdAt.epochSeconds,
                keyPackageEventId = peerKeyPackageEventId,
                relays = relays.map { NormalizedRelayUrl(it) },
                nostrGroupId = nostrGroupId,
            )
            // Compute welcomeEventId
            val welcomeEventId = EventHasher.hashId(
                pubKey = userPublicKey,
                kind = welcomeEvent.kind,
                tags = welcomeEvent.tags,
                createdAt = welcomeEvent.createdAt,
                content = welcomeEvent.content
            )
            logger.d("Welcome Event: ${welcomeEvent.toJson()}")
            logger.d("Welcome Event Id: $welcomeEventId")


            database.giftWrapPayloadDao().insert(
                GiftWrapPayload(
                    id = welcomeEventId,
                    publicKey = userPublicKey,
                    chatRoomId = nostrGroupId,
                    kind = welcomeEvent.kind,
                    tags = welcomeEvent.tags,
                    content = welcomeEvent.content,
                    createdAt = Instant.fromEpochSeconds(welcomeEvent.createdAt)
                )
            )

            // The invite's own transcript line is not written here. It used to be, and
            // that put nothing in the room for an invite into an established group --
            // where this runs a relay round trip later, if at all, and never at all if
            // the ack does not come. It also hung off the two lookups below, so a
            // missing key package or profile cost the line and not just the name.
            // `inviteMember` writes it when the invite is made instead; what this
            // function still owes the room is the failure below.
            database.marmotKeyPackageDao().getMarmotKeyPackageById(peerKeyPackageEventId)?.let { marmotKeyPackage ->
                // The group's signed work, offered to the member being invited.
                // Nothing else will ever show it to them: MLS gives a joiner no
                // history, and a group-signed event never travels -- every device
                // derives it from a session it took part in, or does without. See
                // docs/member-chronicle.md.
                //
                // **Queued behind the Welcome is not delivered after it.** These
                // are different transports -- a relay-borne gift wrap and a
                // kind:445 -- with no ordering between them, and a page that
                // arrives before the invitee has processed their Welcome is from
                // an epoch ahead of theirs and is dropped outright rather than
                // deferred. So this is a latency optimisation and not the
                // mechanism: what recovers it is the invitee asking for
                // themselves once they are in, which `ChronicleManager.requestIfEmpty`
                // does on their first open of the room.
                //
                // A push that does not land is therefore the ordinary case rather
                // than an error, and nothing here reports one to the inviter. It
                // is inside this function's catch for that reason, alongside the
                // Welcome it rides behind.
                ChronicleManager.sendTo(
                    database = database,
                    chatRoomId = nostrGroupId,
                    userPublicKey = userPublicKey,
                    recipient = marmotKeyPackage.publicKey,
                )
            }

            return true
        } catch (e: Throwable) {
            logger.e("Failed to deliver welcome:", e)

            // The room's only account of this. Its own runCatching because the
            // failure being reported may well be the database, and a throw from
            // here would be one the callers on the immediate path do not expect --
            // taking the invite's transaction down over a line about it.
            runCatching {
                announceInviteFailed(
                    chatRoomId = nostrGroupId,
                    userPublicKey = userPublicKey,
                    peerPublicKey = database.marmotKeyPackageDao()
                        .getMarmotKeyPackageById(peerKeyPackageEventId)?.publicKey,
                    reason = e.message,
                )
            }.onFailure { logger.e("Failed to record the undelivered welcome:", it) }

            return false
        }
    }

    private fun encryptedCommitEvent(
        mlsGroup: MlsGroup,
        commitBytes: ByteArray,
        exporterKey: ByteArray? = null,
    ): String {
        val outerKey = exporterKey ?: mlsGroup.exporterSecret()

        return GroupEventEncryption.encrypt(commitBytes, outerKey)
    }

    @Transaction
    open suspend fun encryptAndSendMarmotInnerEvent(
        localChatRoom: LocalChatRoom,
        mlsGroup: MlsGroup,
        marmotInnerEvent: MarmotInnerEvent,
        nostrSignerSync: NostrSignerSync
    ): GroupEvent {
        // The room's profile decides the shape of what goes into MLS. See
        // docs/marmot-v2.md, Phase 2: it is the feature flag for every wire change
        // an old build cannot read, because a current-profile room admits only
        // leaves that carry an identity proof, and only a build past Phase 3 can
        // make one.
        val isCurrentProfileRoom = mlsGroup.currentGroupState().isCurrentProfile

        val plainTextBytes: ByteArray = marmotInnerEvent.directMessageRecipientPublicKey?.let { recipient ->
            // A direct message is gift wrapped for one member before it goes into
            // MLS, so what the group carries is a kind:1059 whose id is not this
            // row's. Everything downstream -- MLS encrypt, outer ChaCha20, kind:445,
            // persistence, broadcast -- is the same either way. See
            // MarmotDirectMessage.
            val wrap = MarmotDirectMessage.wrap(
                signer = nostrSignerSync,
                recipientPublicKey = recipient,
                kind = marmotInnerEvent.kind,
                createdAt = marmotInnerEvent.createdAt.epochSeconds,
                tags = marmotInnerEvent.tags,
                content = marmotInnerEvent.content
            )

            if (isCurrentProfileRoom) {
                // Inside an unsigned envelope authored by the sender, which is what
                // a conformant peer accepts. The bare wrap is signed and names a
                // throwaway key, and fails their author check twice over.
                MarmotDirectMessage.envelope(
                    senderPublicKey = nostrSignerSync.pubKey,
                    recipientPublicKey = recipient,
                    wrap = wrap,
                    createdAt = marmotInnerEvent.createdAt.epochSeconds,
                ).encodeToPayload()
            } else {
                // The bare wrap, signature and all: the one shape every build in a
                // legacy room reads, through the carve-out in mip03Rejection.
                wrap.toJson().encodeToByteArray()
            }
        } ?: run {
            val rumor = RumorAssembler.assembleRumor<Event>(
                pubKey = nostrSignerSync.pubKey,
                ev = EventTemplate(
                    createdAt = marmotInnerEvent.createdAt.epochSeconds,
                    content = marmotInnerEvent.content,
                    tags = marmotInnerEvent.tags,
                    kind = marmotInnerEvent.kind
                )
            )

            // The canonical Marmot app event: six members and no `sig`. The rumor
            // serialised as an Event carries `"sig":""`, which a conformant decoder
            // refuses outright -- a payload with a signature would be a standalone
            // relay event, republishable as a signed statement by its author. The
            // id does not move, because NIP-01 never hashed the signature, so every
            // row filed under this event's id stays valid, and every build already
            // in the field reads the shape: Event.fromJsonOrNull defaults a missing
            // signature. This switch alone needs no gating.
            MarmotAppEvent.fromEvent(rumor).encodeToPayload()
        }

        logger.d("plainTextBytes: ${plainTextBytes.toHex()}")

        // Step 1: MLS encrypt
        val mlsCipherText = mlsGroup.encrypt(plainTextBytes)
        logger.d("mlsCipherText: ${mlsCipherText.toHex()}")

        // Step 2: Outer ChaCha20-Poly1305 encryption
        val exportKey = mlsGroup.exporterSecret()
        logger.d("exporterKey: ${exportKey.toHex()}")
        val encryptedContent = GroupEventEncryption.encrypt(
            mlsMessageBytes = mlsCipherText,
            groupKey = exportKey
        )
        logger.d("encryptedContent: $encryptedContent")

        // Step 3: Build the GroupEvent template (auto-apply NIP-40 expiration
        // if the group has disappearing_message_secs configured per MIP-01/03).
        val createdAt = TimeUtils.now()
        val expirationTime = disappearingExpiration(mlsGroup, createdAt)

        // No `encoding` tag. `transports/nostr.md`: a sender MUST NOT add one to any
        // event shape -- a receiver that switched decoders on it could be steered
        // into a different parse of the same bytes -- and the commit template below
        // never carried one either.
        val template = GroupEvent.build(
            encryptedContentBase64 = encryptedContent,
            nostrGroupId = localChatRoom.chatRoom.id,
            createdAt = createdAt,
        ) {
            if (expirationTime != null) expiration(expirationTime)
        }

        // Step 4: Sign with a fresh ephemeral keypair
        val ephemeralSigner = NostrSignerInternal(KeyPair())
        val groupEvent: GroupEvent = ephemeralSigner.sign(template)
        logger.d("groupEvent: ${groupEvent.toJson()}")

        // May need to update chatRoom mlsGroupState... or last sent message
        database.chatRoomDao().upsert(
            localChatRoom.chatRoom.copy(
                mlsGroupState = mlsGroup.saveState().encodeTls().toHex()
            )
        )

        database.nostrEventDao().upsert(
            NostrEvent(
                id = groupEvent.id,
                tags = groupEvent.tags,
                createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                content = groupEvent.content,
                sig = groupEvent.sig,
                kind = groupEvent.kind,
                pubKey = groupEvent.pubKey
            )
        )

        database.marmotGroupEventDao().upsert(
            MarmotGroupEvent(
                id = groupEvent.id,
                createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                expiresAt = groupEvent.expiration()?.let { Instant.fromEpochSeconds(it) },
                encryptedContent = groupEvent.encryptedContent(),
                chatRoomId = localChatRoom.chatRoom.id,
                userPublicKey = groupEvent.pubKey,
                publicKey = groupEvent.pubKey,
                signature = groupEvent.sig,
            )
        )

        database.marmotInnerEventDao().upsert(
            marmotInnerEvent.copy(
                marmotGroupEventId = groupEvent.id,
                // A direct message's plaintext is already on its ChatMessage row, which is
                // the sender's only copy of it. A second one here would be cleartext left
                // in a table that otherwise holds nothing but wire events.
                content = if (marmotInnerEvent.directMessageRecipientPublicKey != null) {
                    ""
                } else {
                    marmotInnerEvent.content
                }
            )
        )

        // Keyed on the queued row, not on `innerEvent.id`. For a direct message those
        // differ -- the row is the rumor, the wire event is the wrap built around it --
        // and the wrap's id matches no ChatMessage, so this lookup comes back null. That
        // costs only the transcript linkage, not the send.
        val chatMessageOrNull = database.chatMessageDao().getChatMessagesByMarmotInnerEventId(marmotInnerEvent.id)

        logger.d("chatMessageOrNull: $chatMessageOrNull")

        val delivery = MarmotDelivery.plan(
            groupEventId = groupEvent.id,
            relays = Relays.DefaultDMRelayList, // TODO: Get these from localChatRoom...
            chatMessage = chatMessageOrNull,
        )

        // Sync broadcast to all the required relays. Unconditional, and before any
        // transcript bookkeeping: see MarmotDelivery for what gating it on a chat line
        // cost the signing sessions that have none.
        val broadcastNostrEventRequestIds =
            database.broadcastNostrEventRequestDao().insert(delivery.broadcasts)

        chatMessageOrNull?.let { chatMessage ->
            database.chatMessageNostrEventRelationDao().upsert(
                ChatMessageNostrEventRelation(
                    chatMessageId = chatMessage.id,
                    nostrEventId = groupEvent.id
                )
            )

            database.chatMessageDao().upsert(
                chatMessage.copy(
                    marmotGroupEventId = groupEvent.id
                )
            )

            broadcastNostrEventRequestIds.forEach { broadcastNostrEventRequestId ->
                database.chatMessageBroadcastNostrEventRequestRelationDao().upsert(
                    ChatMessageBroadcastNostrEventRequestRelation(
                        chatMessageId = chatMessage.id,
                        broadcastNostrEventRequestId = broadcastNostrEventRequestId
                    )
                )
            }
        }

        return groupEvent
    }

    companion object {
        const val TAG = "MarmotOutboundDao"
    }
}