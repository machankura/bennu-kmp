package com.machankura.bennu.compose.database.repository

import com.machankura.bennu.compose.database.GENESIS_AT
import com.machankura.bennu.compose.database.BennuDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.machankura.bennu.compose.database.model.MarmotRouting
import com.machankura.bennu.compose.nostr.MarmotProfile
import com.machankura.bennu.compose.identity.Identity
import com.machankura.bennu.compose.identity.toKeyPair
import com.machankura.bennu.compose.database.model.GroupKeyState
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.managers.ChronicleManager
import com.machankura.bennu.compose.managers.FrostSigningManager
import com.machankura.bennu.compose.managers.GroupKeyStateManager
import com.machankura.bennu.compose.managers.MarmotGroupCreation
import com.machankura.bennu.compose.managers.SubgroupManager
import com.machankura.bennu.compose.nostr.GroupNostrProfile
import com.machankura.bennu.compose.nostr.GroupCuratedEntry
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.GroupPost
import com.machankura.bennu.compose.nostr.curated.CuratedEntryEvent
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.GroupRelayList
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.database.model.ChatMessage
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GiftWrapPayload
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.database.model.MarmotKeyPackage
import com.machankura.bennu.compose.database.model.Participant
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatMessage
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.types.MarmotReindexReport
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * @param activeIdentity the identity the app runs as. Read by the one write here that
 *   has to sign as it -- creating a direct-message room on the current profile puts
 *   an account identity proof in the creator's leaf -- and by nothing else; a caller
 *   that has only the public key still gets a legacy room without it.
 */
class DatabaseChatRepository(
    private val database: BennuDatabase,
    private val scope: CoroutineScope,
    private val activeIdentity: StateFlow<Identity?> = MutableStateFlow(null),
): com.machankura.bennu.compose.repository.ChatRepository {
    val logger = Logger.withTag(TAG)

    override suspend fun createMarmotGroup(
        groupId: String,
        name: String,
        purpose: String,
        adminPublicKeys: Set<HexKey>,
        userPublicKey: HexKey,
        keyPair: KeyPair,
        path: List<Long>,
        parentChatRoomId: HexKey?,
    ): MarmotGroupCreation.Outcome = MarmotGroupCreation.create(
        database = database,
        chatRepository = this,
        groupId = groupId,
        name = name,
        purpose = purpose,
        adminPublicKeys = adminPublicKeys,
        userPublicKey = userPublicKey,
        keyPair = keyPair,
        path = path,
        parentChatRoomId = parentChatRoomId,
    )
    override suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>> {
        return database.chatRoomDao().observeChatRoomListByUserPublicKey(publicKey)
    }

    override suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom> {
        return database.chatRoomDao().getChatRoomListByUserPublicKey(publicKey)
    }

    override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? {
        return database.chatRoomDao().findChatRoomById(id)
    }

    override suspend fun requestGroupHistoryIfMissing(
        chatRoomId: String,
        userPublicKey: HexKey
    ): Boolean = ChronicleManager.requestIfEmpty(
        database = database,
        chatRoomId = chatRoomId,
        userPublicKey = userPublicKey,
    )

    override suspend fun groupKeyState(chatRoomId: String): GroupKeyState? =
        GroupKeyStateManager.keyStateFor(database, chatRoomId)

    override suspend fun signedGroupKeyStateEvent(chatRoomId: String): GroupSignedEvent? = try {
        GroupKeyStateManager.signedEventFor(database, chatRoomId)
    } catch (e: Throwable) {
        // Reading it walks every key state this device holds through the same
        // checks that let one be believed in the first place, and one that will
        // not derive throws rather than answering. The screen wants to draw
        // either way -- what it loses is the copyable event, not the room.
        logger.e("Error reading the signed key state event for $chatRoomId", e)
        null
    }


    override suspend fun subgroupsOf(chatRoomId: String): List<SubgroupManager.Subgroup> = try {
        SubgroupManager.subgroupsOf(database, chatRoomId)
    } catch (e: Throwable) {
        // A room that shows no subgroups is wrong and survivable; one that will
        // not open because reading them threw is neither.
        logger.e("Error reading the subgroups of $chatRoomId", e)
        emptyList()
    }

    override suspend fun parentOf(chatRoomId: String): HexKey? = try {
        SubgroupManager.parentOf(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading the parent of $chatRoomId", e)
        null
    }

    override suspend fun groupNostrProfile(chatRoomId: String): GroupNostrProfile? = try {
        GroupNostrProfile.newestAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, MetadataEvent.KIND),
            chatRoomId = chatRoomId,
        )
    } catch (e: Throwable) {
        // Reading one parses content off the wire and checks a signature, and a
        // room that shows no profile is wrong and survivable where one that will
        // not open is neither -- the same trade the readings above make.
        logger.e("Error reading the nostr profile of $chatRoomId", e)
        null
    }

    override suspend fun groupRelayLists(chatRoomId: String): List<GroupRelayList> = try {
        // One query per set rather than one for the room: the four kinds are
        // indexed and a room's whole signed history is every artifact, chunk and
        // translation it has ever agreed.
        GroupRelaySet.entries.map { set ->
            GroupRelayList.newestAmong(
                signedEvents = database.groupSignedEventDao()
                    .getByChatRoomIdAndKind(chatRoomId, set.kind),
                chatRoomId = chatRoomId,
                set = set,
            )
        }
    } catch (e: Throwable) {
        logger.e("Error reading the relay lists of $chatRoomId", e)
        // Four unsigned, empty sets: the screen draws "not set yet" rather than
        // failing to draw, which is the trade every reading above makes.
        GroupRelayList.allAmong(emptyList(), chatRoomId)
    }

    override suspend fun groupPosts(chatRoomId: String): List<GroupPost> = try {
        GroupPost.newestFirstAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, TextNoteEvent.KIND),
            chatRoomId = chatRoomId,
        )
    } catch (e: Throwable) {
        logger.e("Error reading the posts of $chatRoomId", e)
        emptyList()
    }

    override suspend fun groupCuratedSchemas(chatRoomId: String): List<GroupCuratedSchema> = try {
        GroupCuratedSchema.newestPerListAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, CuratedSchemaEvent.KIND),
            chatRoomId = chatRoomId,
        )
    } catch (e: Throwable) {
        logger.e("Error reading the curated schemas of $chatRoomId", e)
        emptyList()
    }

    override suspend fun groupCuratedEntries(chatRoomId: String): List<GroupCuratedEntry> = try {
        // The schemas first, because an entry is read against the list it
        // replies to and there is nothing to read one by without them.
        GroupCuratedEntry.newestPerEntryAmong(
            signedEvents = database.groupSignedEventDao()
                .getByChatRoomIdAndKind(chatRoomId, CuratedEntryEvent.CANONICAL_KIND),
            chatRoomId = chatRoomId,
            schemas = groupCuratedSchemas(chatRoomId),
        )
    } catch (e: Throwable) {
        logger.e("Error reading the curated entries of $chatRoomId", e)
        emptyList()
    }

    override suspend fun groupSignedEvent(chatRoomId: String, id: HexKey): GroupSignedEvent? = try {
        database.groupSignedEventDao().getById(id)?.takeIf { it.chatRoomId == chatRoomId }
    } catch (e: Throwable) {
        logger.e("Error reading the signed event $id of $chatRoomId", e)
        null
    }

    override suspend fun canSign(chatRoomId: String): Boolean = try {
        FrostSigningManager.canSign(database, chatRoomId)
    } catch (e: Throwable) {
        logger.e("Error reading whether $chatRoomId can sign", e)
        false
    }

    override suspend fun refuseSubgroup(
        parentChatRoomId: String,
        adminPublicKeys: Set<HexKey>,
        coordinatorPublicKey: HexKey,
    ): String? = try {
        val parentRoom = database.chatRoomDao().findChatRoomById(parentChatRoomId)
            ?: return "Couldn't load this group."

        SubgroupManager.refuseSubgroup(
            database = database,
            parentRoom = parentRoom,
            adminPublicKeys = adminPublicKeys,
            coordinatorPublicKey = coordinatorPublicKey,
        )
    } catch (e: Throwable) {
        // A refusal that cannot be computed has to refuse. The alternative is
        // opening a ceremony whose guards never ran.
        logger.e("Error checking whether $parentChatRoomId can take a subgroup", e)
        "Couldn't check this subgroup. Please try again."
    }
    override suspend fun getAllParticipantsWithPubKey(publicKey: HexKey): List<Participant> {
        return database.participantDao().findParticipantByPublicKey(publicKey)
    }

    override suspend fun findDirectMessageRoom(userPublicKey: HexKey, peerPublicKey: HexKey): LocalChatRoom? {
        val theTwoOfUs = setOf(userPublicKey, peerPublicKey)

        for (participant in database.participantDao().findParticipantByPublicKey(peerPublicKey)) {
            val localChatRoom = database.chatRoomDao().findChatRoomById(participant.chatRoomId) ?: continue
            val members = localChatRoom.localParticipants.map { it.participant.participantPublicKey }.toSet()
            if (members == theTwoOfUs) return localChatRoom
        }

        return null
    }
    override suspend fun getChatMessageListByChatRoomId(chatRoomId: String): List<LocalChatMessage> {
        return database.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId)
    }

    override suspend fun observeChatMessageListByChatRoomId(chatRoomId: String): Flow<List<LocalChatMessage>> {
        return database.chatMessageDao().observeChatMessagesByChatRoomId(chatRoomId)
    }


    override suspend fun getOrCreateChatRoom(
        chatRoomId: String,
        activeUserPublicKey: HexKey,
        relayHint: String?,
        defaultSubject: String?,
        description: String?,
        mlsGroupState: String?
    ): LocalChatRoom? = try {
        return database.nostrNip17Dao().getOrCreateChatRoom(
            chatRoomId = chatRoomId,
            activeUserPublicKey = activeUserPublicKey,
            relayHint = relayHint,
            defaultSubject = defaultSubject,
            description = description,
            mlsGroupState = mlsGroupState
        )
    } catch (e: Throwable) {
        logger.e("Error getting or creating chat", e)
        return null
    }

    override suspend fun getChatMessageRelayForPublicKey(publicKey: HexKey): ChatMessageRelayListEvent? {
        // TODO: Implement a UserChatMessageRelayList model
        return database.nostrEventDao().getAuthoredNostrEvents(
            kinds = arrayOf(
                ChatMessageRelayListEvent.KIND
            ),
            authors = arrayOf(
                publicKey
            ),
            since = GENESIS_AT,
            limit = 5
        ).firstOrNull()?.let { nostrEvent ->
            if (nostrEvent.kind != ChatMessageRelayListEvent.KIND) {
                logger.e("getAuthoredNostrEvents returned invalid ChatMessageRelayListEvent for ${publicKey}: $nostrEvent")
                null
            } else {
                ChatMessageRelayListEvent(
                    id = nostrEvent.id,
                    tags = nostrEvent.tags,
                    pubKey = nostrEvent.pubKey,
                    content = nostrEvent.content,
                    createdAt = nostrEvent.createdAt.epochSeconds,
                    sig = nostrEvent.sig
                )
            }
        }
    }

    override suspend fun getMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): MarmotKeyPackage? =
        when (roomProfile) {
            MarmotProfile.LEGACY -> database.marmotKeyPackageDao().getMarmotKeyPackageForPublicKey(publicKey)
            MarmotProfile.CURRENT -> database.marmotKeyPackageDao()
                .getMarmotKeyPackageForPublicKeyAndProfile(publicKey, MarmotProfile.CURRENT.wireName)
        }

    override suspend fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): Flow<MarmotKeyPackage?> =
        when (roomProfile) {
            MarmotProfile.LEGACY -> database.marmotKeyPackageDao().observeMarmotKeyPackageForPublicKey(publicKey)
            MarmotProfile.CURRENT -> database.marmotKeyPackageDao()
                .observeMarmotKeyPackageForPublicKeyAndProfile(publicKey, MarmotProfile.CURRENT.wireName)
        }

    override suspend fun createNip17ChatRoom(
        userPublicKey: HexKey,
        participantPublicKeys: List<HexKey>,
        subject: String?,
        description: String?
    ): LocalChatRoom? = try {
        database.nostrNip17Dao().createNip17ChatRoom(
            userPublicKey = userPublicKey,
            participantPublicKeys = participantPublicKeys,
            subject = subject,
            description = description
        )
    } catch (e: Throwable) {
        logger.e("Error creating nip-17 chat room", e)
        null
    }

    override suspend fun createMlsDirectMessage(
        name: String?,
        description: String?,
        userPublicKey: HexKey,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage
    ): String {
        // The active identity's key pair when it is the creator; otherwise a read-only
        // pair, which is enough for a legacy room and refused by the factory for a
        // current-profile one.
        val creator = activeIdentity.value
            ?.takeIf { it.nostrPublicKey.equals(userPublicKey, ignoreCase = true) }
            ?.toKeyPair()
            ?: KeyPair(privKey = null, pubKey = userPublicKey.hexToByteArray())

        return database.marmotOutboundDao().createMlsDirectMessageChatRoom(
            name = name,
            description = description,
            creator = creator,
            peerPublicKey = peerPublicKey,
            peerKeyPackage = peerKeyPackage
        )
    }

    override suspend fun recordMarmotRouting(chatRoomId: HexKey, routingId: HexKey) {
        database.marmotRoutingDao().upsert(MarmotRouting(routingId = routingId, chatRoomId = chatRoomId))
    }

    override suspend fun inviteMember(
        localChatRoom: LocalChatRoom,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage
    ) {
        try {
            database.marmotOutboundDao().inviteMemberToChatRoom(
                localChatRoom = localChatRoom,
                peerPublicKey = peerPublicKey,
                peerKeyPackage = peerKeyPackage
            )
        } catch (e: Throwable) {
            // Outside the transaction that just went down, which is the point: the
            // invite line `inviteMember` writes went with it, so an invite refused
            // for want of MLS state or over a mismatched credential left the room
            // with nothing at all to show. The caller still gets the throw and
            // still puts its own message on the invite screen -- this is the copy
            // that is still there tomorrow.
            logger.e("Failed to invite $peerPublicKey to ${localChatRoom.chatRoom.id}", e)
            runCatching {
                database.marmotOutboundDao().announceInviteFailed(
                    chatRoomId = localChatRoom.chatRoom.id,
                    userPublicKey = localChatRoom.chatRoom.userPublicKey,
                    peerPublicKey = peerPublicKey,
                    reason = e.message,
                )
            }.onFailure { logger.e("Failed to record the refused invite:", it) }
            throw e
        }
    }
    override suspend fun addMembers(
        localChatRoom: LocalChatRoom,
        peers: List<Pair<HexKey, MarmotKeyPackage>>
    ): List<HexKey> = database.marmotOutboundDao().addMembersToChatRoom(
        localChatRoom = localChatRoom,
        peers = peers
    )

    override suspend fun sendChatMessage(
        text: String,
        localChatRoom: LocalChatRoom,
        messageType: Kind,
        directMessageRecipientPublicKey: HexKey?
    ) {
        logger.i("sendChatMessage($text): $localChatRoom")
        val mlsGroup = localChatRoom.chatRoom.toMlsGroup()

        if (mlsGroup != null) {
            // Resolved before anything is written. A recipient outside the room cannot be
            // sent to -- the wrap would be undecryptable by every member including them,
            // while the group still saw that a private message had gone somewhere -- and
            // the room is also where the relay hint for the `p` tag comes from.
            val directMessageRecipient = directMessageRecipientPublicKey?.let { recipient ->
                require(recipient != localChatRoom.chatRoom.userPublicKey) {
                    "Cannot send a direct message to yourself: the wrap's throwaway key means it could never be read back"
                }

                localChatRoom.localParticipants
                    .map { it.participant }
                    .firstOrNull { it.participantPublicKey == recipient }
                    ?: throw IllegalArgumentException(
                        "Cannot send a direct message to $recipient: not a member of ${localChatRoom.chatRoom.id}"
                    )
            }

            // Create ChatRumor...
            val createdAt = Clock.System.now().epochSeconds
            val marmotInnerEventKind = when {
                // A direct message is a NIP-17 chat message that happens to travel inside
                // the group, so it keeps kind:14 instead of being mapped down to the
                // group's kind:9. That is also what tells the two apart on the way back in.
                directMessageRecipient != null -> ChatMessageEvent.KIND
                messageType == ChatMessageEvent.KIND -> ChatEvent.KIND // nip-17 chatMessageEvent.kind is in MLS chatMessage.kind
                else -> messageType
            }

            val marmotInnerEventTags = directMessageRecipient?.let { recipient ->
                arrayOf(
                    PTag.assemble(
                        recipient.participantPublicKey,
                        recipient.relayHint?.let { NormalizedRelayUrl(it) }
                    )
                )
            } ?: emptyArray()

            // For a direct message the queued row IS the rumor the wrap will carry, so
            // this id is the one the recipient arrives at after unwrapping -- and, on the
            // way out, the one the notary uses to find this chat message again. The wrap
            // built around it has its own id, which is why that lookup keys on the row.
            val marmotInnerEventId = EventHasher.hashId(
                pubKey = localChatRoom.chatRoom.userPublicKey,
                createdAt = createdAt,
                tags = marmotInnerEventTags,
                content = text,
                kind = marmotInnerEventKind
            )

            database.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = marmotInnerEventId,
                    publicKey = localChatRoom.chatRoom.userPublicKey,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = marmotInnerEventTags,
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    kind = marmotInnerEventKind,
                    directMessageRecipientPublicKey = directMessageRecipient?.participantPublicKey
                )
            )

            database.chatMessageDao().upsert(
                ChatMessage(
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    senderPublicKey = localChatRoom.chatRoom.userPublicKey,
                    isUserMessage = true,
                    giftWrapPayloadId = null,
                    marmotGroupEventId = null,
                    marmotInnerEventId = marmotInnerEventId,
                    // The sender's only copy: the wrap is sealed with a key that is
                    // discarded, so nothing can reconstruct these words from the
                    // transcript later, on this device or any other.
                    messageType = if (directMessageRecipient != null) {
                        ChatMessage.TYPE_DIRECT_MESSAGE
                    } else {
                        "message"
                    },
                    directMessageRecipientPublicKey = directMessageRecipient?.participantPublicKey
                )
            )
        } else {
            // Send GiftWrapMessage
            val receiverTags = localChatRoom.localParticipants.filter { participant ->
                participant.participant.participantPublicKey != localChatRoom.chatRoom.userPublicKey // TODO: Allow this where it's notes to self...
            }.map { localParticipant ->
                PTag.assemble(
                    localParticipant.participant.participantPublicKey,
                    localParticipant.participant.relayHint?.let { NormalizedRelayUrl(it) }
                )
            }

            val createdAt = Clock.System.now().epochSeconds
            val giftWrapPayloadId = EventHasher.hashId(
                pubKey = localChatRoom.chatRoom.userPublicKey,
                createdAt = createdAt,
                tags = receiverTags.toTypedArray(),
                content = text,
                kind = messageType
            )

            database.giftWrapPayloadDao().upsert(
                GiftWrapPayload(
                    id = giftWrapPayloadId,
                    kind = messageType,
                    tags = receiverTags.toTypedArray(),
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    publicKey = localChatRoom.chatRoom.userPublicKey
                )
            )

            database.chatMessageDao().upsert(
                ChatMessage(
                    content = text,
                    chatRoomId = localChatRoom.chatRoom.id,
                    senderPublicKey = localChatRoom.chatRoom.userPublicKey,
                    isUserMessage = true,
                    giftWrapPayloadId = giftWrapPayloadId,
                    marmotGroupEventId = null,
                    marmotInnerEventId = null
                )
            )
        }
    }

    override fun updateChatRoomSubject(
        publicKey: String,
        subject: String
    ): com.machankura.bennu.compose.database.model.ChatRoom? {
        // TODO: Update chatRoom with subject...
        return null
    }

    override suspend fun observeUnsealedGiftWrapPayloads(publicKey: HexKey): Flow<List<GiftWrapPayload>> {
        return database.giftWrapPayloadDao().observeUnsealedGiftWrapPayloads(publicKey)
    }

    override suspend fun sealGiftWrapPayload(
        giftWrapPayload: GiftWrapPayload,
        nostrSignerSync: NostrSignerSync
    ) {
        // An MLS room must never produce a NIP-17 gift wrap of its *contents*. Its messages
        // already travel inside kind:445, and its direct messages are real, correctly signed
        // NIP-59 wraps -- indistinguishable from something this path would be right to
        // publish. The only thing keeping one off a relay is that it never becomes a
        // GiftWrapPayload, so refuse here too rather than trusting every future caller to
        // know that. See docs/marmot-direct-messages.md.
        //
        // The Welcome is the one thing an MLS room is *supposed* to publish this way, and
        // the reason it reaches this path at all. MIP-02 addresses kind:444 to a joiner who
        // holds no group state yet: they cannot read a kind:445, so a relay-borne gift wrap
        // is the only way to reach them, and `MarmotOutboundDao.deliveryWelcome` queues one
        // here deliberately. Every Welcome carries its room's nostrGroupId, so a refusal
        // keyed on `mlsGroupState` alone catches all of them and no invite is ever
        // delivered. The refusal below no longer strands the rest of the queue behind it
        // -- the notary is handed the whole backlog and refuses one payload at a time --
        // but a refused Welcome is still an invite that never arrives, so the exception
        // for it stays.
        if (giftWrapPayload.kind != WelcomeEvent.KIND) {
            val chatRoom = database.chatRoomDao().findChatRoomById(giftWrapPayload.chatRoomId)
            if (chatRoom?.chatRoom?.mlsGroupState != null) {
                logger.e(
                    "Refusing to seal payload ${giftWrapPayload.id}: ${giftWrapPayload.chatRoomId} is an MLS room, " +
                        "and sealing would broadcast it to relays"
                )
                return
            }
        }

        database.participantDao().findParticipantsByChatRoomId(giftWrapPayload.chatRoomId).forEach { participant ->
            if (giftWrapPayload.kind == WelcomeEvent.KIND) {
                logger.i("Only giftWrap welcomeEvent payload (${giftWrapPayload.id}) to the participant who published the related keyPackage")
                val welcomeEvent = WelcomeEvent(
                    id = giftWrapPayload.id,
                    pubKey = nostrSignerSync.pubKey,
                    tags = giftWrapPayload.tags,
                    content = giftWrapPayload.content,
                    createdAt = giftWrapPayload.createdAt.epochSeconds,
                    sig = ""
                )

                val shouldBeGiftWrapped: Boolean = welcomeEvent.keyPackageEventId()?.let { keyPackageEventId ->
                    val marmotKeyPackage = database.marmotKeyPackageDao().getMarmotKeyPackageById(
                        keyPackageEventId
                    )

                    logger.i("MarmoteKeyPackage published by ${marmotKeyPackage?.publicKey}")

                    marmotKeyPackage?.publicKey == participant.participantPublicKey
                } ?: false

                if (!shouldBeGiftWrapped) {
                    logger.w("Participant ${participant.participantPublicKey} didn't publish key package for this welcome event")

                    return@forEach
                }
            }

            val wrapperKeyPair = KeyPair()
            val wrapperSigner = NostrSignerSync(
                keyPair = wrapperKeyPair
            )

            // TODO: Don't sign the payload... use event template instead...
            val giftWrapPayloadRumor = RumorAssembler.assembleRumor(
                pubKey = nostrSignerSync.pubKey,
                ev = EventTemplate(
                    createdAt = giftWrapPayload.createdAt.epochSeconds,
                    kind = giftWrapPayload.kind,
                    tags = giftWrapPayload.tags,
                    content = giftWrapPayload.content
                )
            )
            logger.d("GiftWrap Payload Rumor: ${giftWrapPayloadRumor.toJson()}")

            logger.d("Seal to $participant")
            val payload = giftWrapPayloadRumor.toJson()

            val sealedRumorEvent = nostrSignerSync.signNormal<SealedRumorEvent>(
                createdAt = TimeUtils.randomWithTwoDays(),
                kind = SealedRumorEvent.KIND,
                tags = emptyArray(),
                content = nostrSignerSync.nip44Encrypt(
                    plaintext = payload,
                    toPublicKey = participant.participantPublicKey
                )
            )
            logger.d("SealedRumorEvent: ${sealedRumorEvent.toJson()}")

            val giftWrapEvent = wrapperSigner.signNormal<GiftWrapEvent>(
                createdAt = TimeUtils.randomWithTwoDays(),
                kind = GiftWrapEvent.KIND,
                tags = arrayOf(
                    PTag.assemble(participant.participantPublicKey, participant.relayHint?.let { NormalizedRelayUrl(it) })
                ),
                content = wrapperSigner.nip44Encrypt(
                    plaintext = sealedRumorEvent.toJson(),
                    toPublicKey = participant.participantPublicKey
                )
            )
            logger.d("giftWrapEvent: ${giftWrapEvent.toJson()}")

            database.nostrNip17Dao().persistAndBroadcastGiftWrap(
                giftWrapPayload = giftWrapPayload,
                sealedRumorEvent = sealedRumorEvent,
                giftWrapEvent = giftWrapEvent,
                receiverPublicKey = PTag(
                    pubKey = participant.participantPublicKey,
                    relayHint = participant.relayHint?.let { NormalizedRelayUrl(it) }
                )
            )
        }
    }

    override suspend fun leaveChatRoom(
        localChatRoom: LocalChatRoom,
        onCompletion: () -> Unit
    ) {
        // A current-profile room is left by a SelfRemove proposal the admins commit,
        // the local state dropped and the room closed here -- see MarmotOutboundDao.leave.
        // A legacy room, or one this device holds no state for, is left the way it
        // always was: the flag, and a line.
        if (localChatRoom.chatRoom.marmotProfile() == MarmotProfile.CURRENT && localChatRoom.chatRoom.leftGroupAt == null) {
            database.marmotOutboundDao().leave(localChatRoom)
            onCompletion.invoke()
            return
        }

        database.chatRoomDao().upsert(
            chatRoom = localChatRoom.chatRoom.copy(
                leftGroupAt = Clock.System.now()
            )
        )

        // TODO: Process this in the notary...
        sendChatMessage(
            text = "Left group",
            localChatRoom = localChatRoom,
            // TODO: Add Information/LeaveChatGroup Message Type...
        )

        onCompletion.invoke()
    }

    override suspend fun removeMember(localChatRoom: LocalChatRoom, memberPublicKey: HexKey) {
        database.marmotOutboundDao().removeMember(localChatRoom, memberPublicKey)
    }

    override suspend fun disbandChatRoom(localChatRoom: LocalChatRoom) {
        database.marmotOutboundDao().disband(localChatRoom)
    }

    override suspend fun softDeleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    ) {
        database.chatRoomDao().upsert(
            chatRoom.copy(
                deletedAt = Clock.System.now()
            )
        )

        onCompletion.invoke()
    }

    override suspend fun deleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    ) {
        database.chatRoomDao().delete(chatRoom)

        onCompletion.invoke()
    }

    /**
     * Only the public key is needed: reading a group event is done with the MLS
     * group's own secrets, held in the room's stored state, and the identity is
     * used for nothing but deciding whether this device is a member and whether a
     * line is its own. So a read-only [KeyPair] is enough, and the nostr private
     * key stays where it is.
     */
    override suspend fun reindexMarmotGroupEvents(
        chatRoomId: String,
        userPublicKey: HexKey
    ): MarmotReindexReport =
        database.nostrDao().reindexMarmotGroupEvents(
            chatRoomId = chatRoomId,
            activeKeyPair = KeyPair(pubKey = userPublicKey.hexToByteArray()),
        )

    companion object {
        const val TAG = "DatabaseChatRepository"
    }
}