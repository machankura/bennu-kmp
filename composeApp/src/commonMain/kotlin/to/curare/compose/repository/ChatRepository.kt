package to.curare.compose.repository

import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GiftWrapPayload
import to.curare.compose.database.model.GroupKeyState
import to.curare.compose.database.model.GroupSignedEvent
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import to.curare.compose.database.model.MarmotKeyPackage
import to.curare.compose.managers.SharedKeyDerivation
import to.curare.compose.managers.MarmotGroupCreation
import to.curare.compose.managers.SubgroupManager
import to.curare.compose.nostr.GroupNostrProfile
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupPost
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.database.model.Participant
import to.curare.compose.database.model.intermdiate.LocalChatMessage
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.types.MarmotReindexReport
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ChatRepository {
    suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>>

    suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom>

    suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom?

    /**
     * Ask the group for its signed history if this device holds none of it.
     *
     * True when a request went out. Safe to call on every open: it is a no-op
     * for a room that already holds work and for one still waiting on an answer.
     * See docs/member-chronicle.md for why the joiner asks rather than the inviter
     * pushing.
     */
    suspend fun requestGroupHistoryIfMissing(chatRoomId: String, userPublicKey: HexKey): Boolean

    /**
     * What the group has said this room signs with, or null while it has said
     * nothing.
     *
     * The row the app resolves a signing request against -- see
     * `FrostSigningManager.completedKey` -- and the thing the group detail screen
     * puts at the top, because which key a room signs as is the one fact about a
     * room that decides whether anything it signs will be believed.
     */
    suspend fun groupKeyState(chatRoomId: String): GroupKeyState?

    /**
     * The event the group signed to say it, as the group signed it.
     *
     * Kept apart from [groupKeyState] because they can be missing separately and
     * mean different things when they are. The state is this device's reading;
     * this is the statement, with the signature on it, for a member who wants to
     * see or check what the group actually put its key to.
     */
    suspend fun signedGroupKeyStateEvent(chatRoomId: String): GroupSignedEvent?

    /**
     * Every subgroup this room has certified, as far as this device knows.
     *
     * Read off the parent's own signed certificates rather than off a table, so
     * it includes children whose room this device does not have -- which is the
     * normal position of a member who is not in the subgroup. See
     * `SubgroupManager.subgroupsOf`.
     */
    suspend fun subgroupsOf(chatRoomId: String): List<SubgroupManager.Subgroup>

    /**
     * The group this room is a subgroup of, verified, or null if it is nobody's
     * child.
     *
     * Off the room's `GroupKeyState`, which only carries a parent whose
     * certificate was checked. A member welcomed in after the founding holds no
     * state and gets null here even when the room really is a subgroup.
     */
    suspend fun parentOf(chatRoomId: String): HexKey?

    /**
     * The kind:0 this group signed about itself, or null while it has signed
     * none.
     *
     * The group's identity on nostr is the room's own id, so it has one to
     * describe whether or not it ever has. Read off the group's signed events and
     * verified there -- see `GroupNostrProfile` -- so a room shows the profile its
     * own key signed or shows that it has none.
     */
    suspend fun groupNostrProfile(chatRoomId: String): GroupNostrProfile?

    /**
     * Where this group has said it lives on nostr: one entry per relay set, always
     * all of them.
     *
     * A set the group has never agreed comes back unsigned and empty rather than
     * missing, because "never said" is a state the screen has to show and a gap in
     * a list is not one. See `GroupRelayList.isAgreed`.
     */
    suspend fun groupRelayLists(chatRoomId: String): List<GroupRelayList>

    /**
     * The kind:1s this group signed, newest first.
     *
     * The group speaking as itself, which is a different thing from a member
     * speaking in the room -- the transcript is the second. Unlike the profile and
     * the relay lists these accumulate rather than replace, so this is a list and
     * an empty one means the group has said nothing publicly.
     */
    suspend fun groupPosts(chatRoomId: String): List<GroupPost>

    /**
     * The curated schemas this group signed -- the lists it curates -- one per
     * identifier, most recently signed first.
     *
     * Kind 31889 is addressable, so an edit is a newer event under the same
     * identifier and the newest wins per list, the way the profile does; two
     * identifiers are two lists and both come back. Empty in a group that
     * curates nothing, which is every group until somebody proposes one.
     */
    suspend fun groupCuratedSchemas(chatRoomId: String): List<GroupCuratedSchema>

    /**
     * One event the group signed, as the group signed it, or null when this
     * device does not hold it or it was signed in some other room.
     *
     * Scoped to the room rather than looked up by id alone, because the id
     * arrives as a navigation argument and the screen it opens names a room in
     * its title: an event from one room shown under another room's name would
     * be a lie the signature check cannot catch, since both rooms' events verify.
     */
    suspend fun groupSignedEvent(chatRoomId: String, id: HexKey): GroupSignedEvent?

    /**
     * Whether this device holds a share of the group's key, and so could take
     * part in signing for it.
     *
     * Read by the UI so nothing is offered that would throw:
     * `FrostSigningManager.proposeSigningBatch` refuses outright for a device
     * with no share, and a button that throws is not a button.
     */
    suspend fun canSign(chatRoomId: String): Boolean

    /**
     * Why these members cannot hold a subgroup ceremony together, or null when
     * they can.
     *
     * Read by the picker so a subgroup that could only fail is never offered, and
     * again on confirm, because a screen not drawing something is not a guard.
     * See `SubgroupManager.refuseSubgroup`.
     */
    suspend fun refuseSubgroup(
        parentChatRoomId: String,
        adminPublicKeys: Set<HexKey>,
        coordinatorPublicKey: HexKey,
    ): String?

    suspend fun getAllParticipantsWithPubKey(publicKey: HexKey): List<Participant>

    suspend fun getChatMessageListByChatRoomId(chatRoomId: String): List<LocalChatMessage>

    suspend fun observeChatMessageListByChatRoomId(chatRoomId: String): Flow<List<LocalChatMessage>>

    suspend fun getOrCreateChatRoom(
        chatRoomId: String,
        activeUserPublicKey: HexKey,
        relayHint: String?,
        defaultSubject: String?,
        description: String?,
        mlsGroupState: String?
    ): LocalChatRoom?

    suspend fun getChatMessageRelayForPublicKey(publicKey: HexKey): ChatMessageRelayListEvent?

    suspend fun getMarmotKeyPackageForPublicKey(publicKey: HexKey): MarmotKeyPackage?

    suspend fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey): Flow<MarmotKeyPackage?>

    /**
     * Creates a NIP-17 group: participants and a room, no MLS group. Membership is
     * the p-tag set carried by each message, so there is nothing to invite anyone to.
     */
    suspend fun createNip17ChatRoom(
        userPublicKey: HexKey,
        participantPublicKeys: List<HexKey>,
        subject: String? = null,
        description: String? = null
    ): LocalChatRoom?

    suspend fun createMlsDirectMessage(
        name: String? = null,
        description: String? = null,
        userPublicKey: HexKey,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage
    ): String

    suspend fun inviteMember(
        localChatRoom: LocalChatRoom,
        peerPublicKey: HexKey,
        peerKeyPackage: MarmotKeyPackage,
    )

    /**
     * Adds every member in one MLS commit, returning those that could not be added.
     *
     * Prefer this to looping [inviteMember] whenever the membership is known up
     * front: one commit means no intermediate epoch for a member to miss, which is
     * a silent failure rather than a loud one. See docs/marmot-membership.md.
     */
    suspend fun addMembers(
        localChatRoom: LocalChatRoom,
        peers: List<Pair<HexKey, MarmotKeyPackage>>,
    ): List<HexKey>

    /**
     * Creates a Marmot room at an id the group's key derives, with
     * [adminPublicKeys] as its admins, and welcomes them into it.
     *
     * Two flows want exactly this: the admin room a group opens after its
     * ceremony, and a subgroup. See `MarmotGroupCreation`, which holds the rules
     * -- every key package before anything exists, the admins baked into epoch 0,
     * and the key state filed before the members are added.
     *
     * Refuses rather than half-creates: the id is derived, so a partly created
     * room occupies that address permanently and there is no second id to retry
     * with.
     */
    suspend fun createMarmotGroup(
        groupId: String,
        name: String,
        purpose: String,
        adminPublicKeys: Set<HexKey>,
        userPublicKey: HexKey,
        keyPair: KeyPair,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
        parentChatRoomId: HexKey? = null,
    ): MarmotGroupCreation.Outcome

    /**
     * Sends [text] to the room, or -- when [directMessageRecipientPublicKey] is given --
     * to that one member of it.
     *
     * A direct message still travels as one group event that every member receives and
     * can see the shape of; only its contents are private. See
     * docs/marmot-direct-messages.md. NIP-17 rooms do not support it: there is no group
     * for the rest of to see it, so a private message there is just a message.
     */
    suspend fun sendChatMessage(
        text: String,
        localChatRoom: LocalChatRoom,
        messageType: Kind = ChatMessageEvent.KIND,
        directMessageRecipientPublicKey: HexKey? = null
    )

    fun updateChatRoomSubject(publicKey: String, subject: String): ChatRoom?

    /**
     * The whole unsealed backlog for [publicKey], oldest first.
     *
     * See `GiftWrapPayloadDao.observeUnsealedGiftWrapPayloads`: a payload that cannot be
     * sealed used to be the entire queue. Callers work through it and a refusal costs
     * only the payload it refuses.
     */
    suspend fun observeUnsealedGiftWrapPayloads(publicKey: HexKey): Flow<List<GiftWrapPayload>>

    suspend fun sealGiftWrapPayload(giftWrapPayload: GiftWrapPayload, nostrSignerSync: NostrSignerSync)

    suspend fun leaveChatRoom(
        localChatRoom: LocalChatRoom,
        onCompletion: () -> Unit
    )

    suspend fun softDeleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    )

    suspend fun deleteChatRoom(
        chatRoom: ChatRoom,
        onCompletion: () -> Unit
    )

    /**
     * Reads the room's stored marmot group events again, so ones the group could
     * not make sense of when they arrived get another chance now that the events
     * they were waiting on have landed.
     *
     * Only events with nothing to show for them are replayed, so a room where
     * nothing went wrong is left exactly as it was.
     */
    suspend fun reindexMarmotGroupEvents(
        chatRoomId: String,
        userPublicKey: HexKey
    ): MarmotReindexReport

    companion object {
        val NO_OP_CHAT_REPOSITORY = object: ChatRepository {
            override suspend fun requestGroupHistoryIfMissing(
                chatRoomId: String,
                userPublicKey: HexKey
            ): Boolean = false

            override suspend fun groupKeyState(chatRoomId: String): GroupKeyState? = null

            override suspend fun signedGroupKeyStateEvent(
                chatRoomId: String
            ): GroupSignedEvent? = null

            override suspend fun subgroupsOf(chatRoomId: String): List<SubgroupManager.Subgroup> =
                emptyList()

            override suspend fun parentOf(chatRoomId: String): HexKey? = null

            override suspend fun groupNostrProfile(chatRoomId: String): GroupNostrProfile? = null

            override suspend fun groupRelayLists(chatRoomId: String): List<GroupRelayList> =
                GroupRelayList.allAmong(emptyList(), chatRoomId)

            override suspend fun groupPosts(chatRoomId: String): List<GroupPost> = emptyList()

            override suspend fun groupCuratedSchemas(
                chatRoomId: String
            ): List<GroupCuratedSchema> = emptyList()

            override suspend fun groupSignedEvent(
                chatRoomId: String,
                id: HexKey
            ): GroupSignedEvent? = null

            override suspend fun canSign(chatRoomId: String): Boolean = false

            override suspend fun refuseSubgroup(
                parentChatRoomId: String,
                adminPublicKeys: Set<HexKey>,
                coordinatorPublicKey: HexKey,
            ): String? = "No database"

            override suspend fun observeChatRoomListByPublicKey(publicKey: String): Flow<List<LocalChatRoom>> {
                return flow {  }
            }

            override suspend fun getChatRoomListByPublicKey(publicKey: String): List<LocalChatRoom> {
                return emptyList()
            }

            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? {
                return null
            }

            override suspend fun getAllParticipantsWithPubKey(publicKey: HexKey): List<Participant> {
                TODO("Not yet implemented")
            }

            override suspend fun getChatMessageListByChatRoomId(chatRoomId: String): List<LocalChatMessage> {
                return emptyList()
            }

            override suspend fun observeChatMessageListByChatRoomId(chatRoomId: String): Flow<List<LocalChatMessage>> {
                return flow {
                }
            }

            override suspend fun getOrCreateChatRoom(
                chatRoomId: String,
                activeUserPublicKey: HexKey,
                relayHint: String?,
                defaultSubject: String?,
                description: String?,
                mlsGroupState: String?
            ): LocalChatRoom? {
                return null
            }

            override suspend fun getChatMessageRelayForPublicKey(publicKey: HexKey): ChatMessageRelayListEvent? {
                return null
            }

            override suspend fun getMarmotKeyPackageForPublicKey(publicKey: HexKey): MarmotKeyPackage? {
                TODO("Not yet implemented")
            }

            override suspend fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey): Flow<MarmotKeyPackage?> {
                TODO("Not yet implemented")
            }

            override suspend fun createNip17ChatRoom(
                userPublicKey: HexKey,
                participantPublicKeys: List<HexKey>,
                subject: String?,
                description: String?
            ): LocalChatRoom? {
                return null
            }

            override suspend fun createMlsDirectMessage(
                name: String?,
                description: String?,
                userPublicKey: HexKey,
                peerPublicKey: HexKey,
                peerKeyPackage: MarmotKeyPackage
            ): String {
                TODO("Not yet implemented")
            }

            override suspend fun inviteMember(
                localChatRoom: LocalChatRoom,
                peerPublicKey: HexKey,
                peerKeyPackage: MarmotKeyPackage
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun addMembers(
                localChatRoom: LocalChatRoom,
                peers: List<Pair<HexKey, MarmotKeyPackage>>
            ): List<HexKey> = peers.map { it.first }

            override suspend fun createMarmotGroup(
                groupId: String,
                name: String,
                purpose: String,
                adminPublicKeys: Set<HexKey>,
                userPublicKey: HexKey,
                keyPair: KeyPair,
                path: List<Long>,
                parentChatRoomId: HexKey?,
            ): MarmotGroupCreation.Outcome =
                MarmotGroupCreation.Outcome.Failed("No database")


            override suspend fun sendChatMessage(
                text: String,
                localChatRoom: LocalChatRoom,
                messageType: Kind,
                directMessageRecipientPublicKey: HexKey?
            ) {
                // TODO("Not yet implemented")
            }

            override fun updateChatRoomSubject(publicKey: String, subject: String): ChatRoom? {
                return null
            }

            override suspend fun observeUnsealedGiftWrapPayloads(publicKey: HexKey): Flow<List<GiftWrapPayload>> {
                TODO("Not yet implemented")
            }

            override suspend fun sealGiftWrapPayload(
                giftWrapPayload: GiftWrapPayload,
                nostrSignerSync: NostrSignerSync
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun leaveChatRoom(
                localChatRoom: LocalChatRoom,
                onCompletion: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun softDeleteChatRoom(
                chatRoom: ChatRoom,
                onCompletion: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun deleteChatRoom(
                chatRoom: ChatRoom,
                onCompletion: () -> Unit
            ) {
                TODO("Not yet implemented")
            }

            override suspend fun reindexMarmotGroupEvents(
                chatRoomId: String,
                userPublicKey: HexKey
            ): MarmotReindexReport = MarmotReindexReport()
        }
    }
}