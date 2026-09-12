package to.curare.compose.database.repository

import to.curare.compose.database.GENESIS_AT
import to.curare.compose.database.model.BroadcastNostrEventRequest
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.UnsignedNostrEvent
import to.curare.compose.database.model.intermdiate.LocalAccount
import to.curare.compose.database.model.intermdiate.LocalBroadcastNostrEventRequest
import to.curare.compose.database.model.types.SynchronizationFilter
import to.curare.compose.database.dao.NostrEventDao
import to.curare.compose.database.query.NostrEventFilterQuery
import to.curare.compose.extensions.toHex
import to.curare.compose.nostr.Relays
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRelayListEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.hints.EventHintBundle
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.tags.hashtags.hashtags
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.tags.references.references
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip02FollowList.RelaySet
import com.vitorpamplona.quartz.nip02FollowList.tags.ContactTag
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip10Notes.content.findHashtags
import com.vitorpamplona.quartz.nip10Notes.content.findNostrUris
import com.vitorpamplona.quartz.nip10Notes.content.findURLs
import com.vitorpamplona.quartz.nip10Notes.tags.markedETags
import com.vitorpamplona.quartz.nip10Notes.tags.notify
import com.vitorpamplona.quartz.nip10Notes.tags.prepareETagsAsReplyTo
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip18Reposts.RepostEvent
import com.vitorpamplona.quartz.nip18Reposts.quotes.QEventTag
import com.vitorpamplona.quartz.nip18Reposts.quotes.quotes
import com.vitorpamplona.quartz.nip21UriScheme.toNostrUri
import com.vitorpamplona.quartz.nip25Reactions.ReactionEvent
import com.vitorpamplona.quartz.nip31Alts.AltTag
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
// NIP-51 has two RelayTag classes with identical `assemble` signatures: the generic list one
// uses "r", while the relay-list events (kind 10007 search, kind 10012 feeds) parse "relay".
// Importing the wrong one compiled cleanly and produced lists nothing could read back.
import com.vitorpamplona.quartz.nip51Lists.relayLists.tags.RelayTag
import com.vitorpamplona.quartz.nip57Zaps.LnZapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

class DatabaseNostrRepository(
    private val database: to.curare.compose.database.CurareDatabase,
    private val scope: CoroutineScope
): to.curare.compose.repository.NostrRepository, to.curare.compose.repository.RelayRepository {
    companion object {
        const val TAG = "DatabaseNostrRepository"

        /** Falls back to the same window the per-shape queries this replaced hard-coded. */
        private const val DEFAULT_NEGENTROPY_LIMIT = 50

        private val storeNostrEventMutex = Mutex()
    }

    val logger = Logger.withTag(TAG)

    override suspend fun getLocalAccounts(): List<LocalAccount> {
        return database.unsignedNostrEventDao().getLocalAccounts()
    }

    override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> {
        return database.unsignedNostrEventDao().observeProfile(publicKey)
    }

    override suspend fun getProfileWithPublicKey(publicKey: HexKey): Profile? {
        return database.profileDao().getProfileByPublicKey(publicKey)
    }

    override suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?> {
        return database.profileDao().observeProfileByPublicKey(publicKey)
    }

    override suspend fun observeRelation(
        publicKey: HexKey,
        activePublicKey: HexKey
    ): Flow<List<to.curare.compose.database.model.Connection>> {
        return database.connectionDao().observeRelation(
            alicePublicKey = publicKey,
            bobPublicKey = activePublicKey
        )
    }

    override suspend fun deleteConnection(connection: to.curare.compose.database.model.Connection) {
        database.connectionDao().delete(connection)
    }

    override suspend fun observeUnsignedNostrEvents(publicKey: HexKey): Flow<List<UnsignedNostrEvent>> {
        return database.unsignedNostrEventDao().observeUnsignedNostrEvents(publicKey)
    }

    override suspend fun observePendingBroadcastNostrEventRequests(): Flow<LocalBroadcastNostrEventRequest?> {
        return database.broadcastNostrEventRequestDao().observeBroadcastNostrEventRequestsByStatus("pending")
    }

    override suspend fun observePendingSynchronizeNostrEventRequests(): Flow<to.curare.compose.database.model.SynchronizeNostrEventRequest?> {
        return database.synchronizeNostrEventRequestDao().observeSynchronizeNostrEventRequestsByStatus("pending")
    }

    override suspend fun observePendingNegentropySynchronizeRequests(): Flow<to.curare.compose.database.model.NegentropySynchronizeRequest?> {
        return database.negentropySynchronizeRequestDao().observeNegentropySynchronizeRequestsByStatus("pending")
    }

    override suspend fun createNewProfile(
        publicKey: HexKey,
        name: String?,
        biography: String?,
        onCompletion: suspend () -> Unit
    ) {
        logger.d("createNewProfile: $publicKey")

        saveUnsignedNostrEvents(bootstrapUnsignedEvents(publicKey, name, biography))

        onCompletion.invoke()
    }

    override suspend fun setUpProfileForExistingKey(
        publicKey: HexKey,
        placeholderUnsignedNostrEventId: Long,
        name: String?,
        biography: String?,
    ) {
        logger.d("setUpProfileForExistingKey: $publicKey")

        val events = bootstrapUnsignedEvents(publicKey, name, biography)
        val profile = events.first { it.kind == MetadataEvent.KIND }

        // The placeholder row is *replaced*, under its own id, rather than joined by a
        // second kind 0: `getLocalAccounts` is every kind-0 row, and two for one pubkey
        // would be two accounts disagreeing about which is this one. Its `signedAt` goes
        // back to null so the notary signs it, and the sync requests that hang off the id
        // stay where they are as the record of the search that found nothing.
        database.unsignedNostrEventDao().upsert(
            profile.copy(
                id = placeholderUnsignedNostrEventId,
                signedAt = null,
            )
        )
        saveUnsignedNostrEvents(events.filterNot { it.kind == MetadataEvent.KIND })
    }

    override suspend fun forgetLocalAccount(publicKey: HexKey) {
        logger.d("forgetLocalAccount: $publicKey")
        database.unsignedNostrEventDao().deleteAllForPublicKey(publicKey)
    }

    /**
     * What a key gets when it first becomes a profile here: the kind 0, a contact list
     * that follows itself, and the relay lists the app reads, all pointed at the defaults.
     * The kind 0 is first.
     */
    private fun bootstrapUnsignedEvents(
        publicKey: HexKey,
        name: String?,
        biography: String?,
    ): List<UnsignedNostrEvent> {
        val profileEventTemplate = MetadataEvent.createNew(
            name = name,
            about = biography,
        )

        val unsignedNostrEvents = mutableListOf(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = profileEventTemplate.kind,
                createdAt = Instant.fromEpochSeconds(profileEventTemplate.createdAt),
                tags = profileEventTemplate.tags,
                content = profileEventTemplate.content,
                signedAt = null
            )
        )

        val followUsers = listOf(ContactTag(publicKey, null, null))
        val contactListTagList = followUsers.map { it.toTagArray() }

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = ContactListEvent.KIND,
                tags = contactListTagList.toTypedArray(),
                content = RelaySet.assemble(
                    emptyMap()
                )
            )
        )

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = ChatMessageRelayListEvent.KIND,
                tags = ChatMessageRelayListEvent.createTagArray(
                    Relays.DefaultDMRelayList
                ),
                content = ""
            )
        )

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = KeyPackageRelayListEvent.KIND,
                tags = Relays.DefaultDMRelayList.map { arrayOf("relay", it.url) }
//                    .plusElement(AltTag.assemble(KeyPackageRelayListEvent.ALT_DESCRIPTION))
                    .toTypedArray(),
                content = ""
            )
        )



        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = SearchRelayListEvent.KIND,
                tags = Relays.DefaultSearchRelayList.map {
                    RelayTag.assemble(
                        it
                    )
                }.toTypedArray(),
                content = ""
            )
        )

        unsignedNostrEvents.add(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = RelayFeedsListEvent.KIND,
                tags = arrayOf(
//                    AltTag.assemble(RelayFeedsListEvent.ALT)
                ),
                privateTags = Relays.DefaultDMRelayList.map {
                    RelayTag.assemble(
                        it
                    )
                }.toTypedArray(),
                content = ""
            )
        )

        return unsignedNostrEvents
    }

    override suspend fun signInToProfile(
        publicKey: HexKey
    ) {
        val profileEventTemplate = MetadataEvent.createNew()

        saveUnsignedNostrEvent(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = profileEventTemplate.kind,
                createdAt = Instant.fromEpochSeconds(profileEventTemplate.createdAt),
                tags = profileEventTemplate.tags,
                content = profileEventTemplate.content,
                signedAt = GENESIS_AT // This will keep us from trying to sign this event... but we should automatically sync the npub profile
            )
        )
    }
    override suspend fun createNewTextNote(
        publicKey: HexKey,
        mentionedPublicKeys: List<HexKey>,
        textInput: String,
        inReplyToNostrEvent: to.curare.compose.database.model.intermdiate.LocalNostrEvent?,
        quotedNostrEvent: to.curare.compose.database.model.intermdiate.LocalNostrEvent?,
        onError: () -> Unit,
        onSuccess: () -> Unit
    ) {
        val quotedNostrURI = quotedNostrEvent?.let {
            // TODO: Convert qoutedNostrEvent to nostrURI
            val quotedTextNoteEvent = it.nostrEvent.toTextNoteEvent()

            val relayHint = null // TODO: get relyhint from where ever the nostrEvent was synced from...

            "\n${quotedTextNoteEvent.toNostrUri(relayHint)}"
        } ?: ""
        val text: String = textInput + quotedNostrURI

        // TODO: Handle mentioned profile tags...
        val textNote = TextNoteEvent.build(
            note = text,
        ) {
            inReplyToNostrEvent?.let {
                val replyToTextNoteEvent = it.nostrEvent.toTextNoteEvent()

                val replyingTo = EventHintBundle(
                    replyToTextNoteEvent
                )

                val tags = prepareETagsAsReplyTo(replyingTo)

                markedETags(
                    tags
                )

                it.profile?.let { profile ->
                    val publicKeys = mutableSetOf<HexKey>()
                    publicKeys.addAll(
                        mentionedPublicKeys.toSet()
                    )

                    publicKeys.add(profile.publicKey)

                    val ptags = publicKeys.map { publicKey ->
                        PTag(
                            publicKey,
                            // TODO: Add relayHints...
                        )
                    }

                    notify(ptags)
                }
            }

            if (inReplyToNostrEvent == null || inReplyToNostrEvent.profile == null) {
                val pTags = mentionedPublicKeys.toSet().map {
                    PTag(
                        pubKey = it
                    )
                }

                notify(pTags)
            }

            quotedNostrEvent?.let {
                it.profile?.let { profile ->
                    val ptags = listOf(
                        PTag(
                            profile.publicKey,
                            // TODO: Add relayHints...
                        ),
                        // Other profiles tagged in the event...
                    )

                    add(
                        QEventTag.assemble(
                            eventId = quotedNostrEvent.nostrEvent.id,
                            relay = null, // TODO: Add relayHints...
                            author = profile.publicKey
                        )
                    )
                    notify(ptags)

                    // TODO: ZapForwarding support...
                }
            }

            hashtags(findHashtags(text))
            references(findURLs(text))
            quotes(findNostrUris(text))
        }


        val unsignedNostrEvent = saveUnsignedNostrEvent(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = textNote.kind,
                createdAt = Instant.fromEpochSeconds(textNote.createdAt),
                tags = textNote.tags,
                content = textNote.content
            )
        )

        if (unsignedNostrEvent == null) {
           onError.invoke()
        } else {
            if (inReplyToNostrEvent != null) {
                // TODO: Schedule inReplyToNostrEvent broadcast
            }

            if (quotedNostrEvent != null) {
                // TODO: Schedule quotedNostrEvent broadcast
            }

            onSuccess.invoke()
        }
    }

    override suspend fun saveUnsignedNostrEvent(unsignedNostrEvent: UnsignedNostrEvent): UnsignedNostrEvent? = try {
        logger.d("Save unsigned: ${unsignedNostrEvent.kind}")
        // TODO: We can actually check the unsignedNostrEvent.pubKey for support... if it's writeable
        if (unsignedNostrEvent.kind == MetadataEvent.KIND){
            delay(2_100) // Just to make it look like we are doing serious work...
        }

        unsignedNostrEvent.copy(
            id = database.unsignedNostrEventDao().upsert(unsignedNostrEvent)
        )
    } catch (e: Throwable) {
        logger.e("Failed to saveUnsignedNostrEvent", e)
        null
    }

    override suspend fun saveUnsignedNostrEvents(unsignedNostrEvents: List<UnsignedNostrEvent>) {
        logger.d("saveUnsignedNostrEvents: $unsignedNostrEvents")

        database.unsignedNostrEventDao().insert(
            unsignedNostrEvents
        )
    }

    override suspend fun wipeDatabase() {
        database.wipeData()
    }

    override suspend fun publishNostrEvent(
        unsignedNostrEvent: UnsignedNostrEvent,
        nostrEvent: NostrEvent,
        relayURLs: List<String>,
        activeKeyPair: KeyPair
    ) {
        database.nostrDao().publishNostrEvent(
            unsignedNostrEvent,
            nostrEvent,
            relayURLs,
            activeKeyPair = activeKeyPair
        )
    }

    override suspend fun requeueStaleBroadcastNostrEventRequests(): Int {
        val requeued = database.broadcastNostrEventRequestDao().requeueStaleBroadcastNostrEventRequests(
            staleBefore = Clock.System.now()
        )
        if (requeued > 0) {
            logger.i("Requeued $requeued stale broadcast request(s) left over from a previous run")
        }
        return requeued
    }

    override suspend fun broadcastProcessed(
        broadcastNostrEventRequest: BroadcastNostrEventRequest,
        status: String
    ) {
        database.broadcastNostrEventRequestDao().upsert(
            broadcastNostrEventRequest.copy(
                status = status
            )
        )

        if (status == "published") {
            val broadcastNostrEventReceiptId = database.broadcastNostrEventReceiptDao().upsert(
                to.curare.compose.database.model.BroadcastNostrEventReceipt(
                    nostrEventId = broadcastNostrEventRequest.nostrEventId,
                    unsignedNostrEventId = broadcastNostrEventRequest.unsignedNostrEventId,
                    isAccepted = true,
                    relayURL = broadcastNostrEventRequest.relayURL
                )
            )

            database.chatMessageBroadcastNostrEventRequestRelationDao().getChatMessageBroadcastNostrEventRequestRelationByBroadcastRequest(
                broadcastNostrEventRequestId = broadcastNostrEventRequest.id
            )?.let { chatMessageBroadcastNostrEventRequestRelation ->
                // This is actually a chatMessage related broadcast event...
                database.chatMessageBroadcastNostrEventReceiptRelationDao().upsert(
                    to.curare.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation(
                        chatMessageId = chatMessageBroadcastNostrEventRequestRelation.chatMessageId,
                        broadcastNostrEventReceiptId = broadcastNostrEventReceiptId
                    )
                )
            }

            database.marmotCommitResultDao().getMarmotCommitRequestById(
                nostrEventId = broadcastNostrEventRequest.nostrEventId
            )?.let { marmotCommitResult ->
                logger.d("marmotCommitResult: $marmotCommitResult")
                if (marmotCommitResult.isOneMemberInitialGroupCreation.not()) {
                    logger.d("Deliver Welcome")
                    database.chatRoomDao().findChatRoomById(marmotCommitResult.chatRoomId)?.let { localChatRoom ->
                        // TODO: Update status of participant Invitation.PENDING -> Invitation.SENT
                        logger.d("localChatRoom: $localChatRoom")
                        val welcomeBytes = marmotCommitResult.welcomeBytes
                        if (welcomeBytes == null) {
                            // The commit was acknowledged and there is nothing to let
                            // the invitee in with, so this invite is over. Nobody is
                            // waiting on the answer by now -- the screen that asked
                            // closed when the commit was made -- which is why it goes
                            // in the room rather than to a caller.
                            database.marmotOutboundDao().announceInviteFailed(
                                chatRoomId = marmotCommitResult.chatRoomId,
                                userPublicKey = marmotCommitResult.userPublicKey,
                                peerPublicKey = database.marmotKeyPackageDao()
                                    .getMarmotKeyPackageById(marmotCommitResult.peerKeyPackageEventId)
                                    ?.publicKey,
                                reason = "the commit carried no invitation",
                            )
                            return@let
                        }

                        logger.d("welcomeBytes: ${welcomeBytes.toHex()}")
                        val delivered = database.marmotOutboundDao().deliveryWelcome(
                            nostrGroupId = marmotCommitResult.chatRoomId,
                            userPublicKey = marmotCommitResult.userPublicKey,
                            welcomeBytes = welcomeBytes,
                            peerKeyPackageEventId = marmotCommitResult.peerKeyPackageEventId,
                            createdAt = marmotCommitResult.createdAt,
                            relays = Relays.DefaultDMRelayList.map { it.url } // TODO: Get localChatRoom relays...
                        )

                        // This is the deferred delivery site, and the only one where
                        // the Welcome goes out later than the invite that made it --
                        // so it is the only one that owes the room a second line. An
                        // invite whose room was still just its creator sent its
                        // Welcome in the same breath and said so once already. A
                        // failure needs nothing here: `deliveryWelcome` files its own.
                        if (delivered) {
                            database.marmotOutboundDao().announceInviteSent(
                                chatRoomId = marmotCommitResult.chatRoomId,
                                userPublicKey = marmotCommitResult.userPublicKey,
                                peerKeyPackageEventId = marmotCommitResult.peerKeyPackageEventId,
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun synchronizeNostrEventRequestProcessed(synchronizeNostrEventRequest: to.curare.compose.database.model.SynchronizeNostrEventRequest) {
        database.synchronizeNostrEventRequestDao().upsert(
            synchronizeNostrEventRequest.copy(
                status = "sent",
                updatedAt = Clock.System.now()
            )
        )
    }

    override suspend fun synchronizeNostrEventRequestCompleted(synchronizeNostrEventRequest: to.curare.compose.database.model.SynchronizeNostrEventRequest) {
        database.synchronizeNostrEventRequestDao().markCompleteIfSent(
            id = synchronizeNostrEventRequest.id,
            status = to.curare.compose.nostr.SignInSync.STATUS_COMPLETE,
            updatedAt = Clock.System.now(),
        )
    }

    override suspend fun negentropySynchronizeRequestProcessed(negentropySynchronizeRequest: to.curare.compose.database.model.NegentropySynchronizeRequest) {
        database.negentropySynchronizeRequestDao().upsert(
            negentropySynchronizeRequest.copy(
                status = "sent",
                updatedAt = Clock.System.now()
            )
        )
    }

    override suspend fun saveBroadcastReceipt(broadcastNostrEventReceipt: to.curare.compose.database.model.BroadcastNostrEventReceipt) {
        database.broadcastNostrEventReceiptDao().upsert(
            broadcastNostrEventReceipt
        )
    }

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        synchronizeNostrEventRequest: to.curare.compose.database.model.SynchronizeNostrEventRequest,
        synchronizationRelayURLs: List<String>,
        activeKeyPair: KeyPair,
    ) {
        storeNostrEventMutex.withLock {
            logger.d("saveNostrEvent: $nostrEvent")
            try {
                database.nostrDao().storeNostrEvent(
                    nostrEvent.copy(
                        broadcastedAt = nostrEvent.createdAt,
                    ),
                    synchronizationRelayURLs = synchronizationRelayURLs,
                    relayURL = synchronizeNostrEventRequest.relayURL,
                    level = synchronizeNostrEventRequest.level,
                    activeKeyPair = activeKeyPair
                )

                database.synchronizeNostrEventRequestDao().upsert(
                    synchronizeNostrEventRequest.copy(
                        nostrEventId = nostrEvent.id,
                        status = "processed"
                    )
                )
            } catch (e: Throwable) {
                logger.e("Failed to save nostrEvent ${nostrEvent.id}", e)
            }

        }
    }

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        negentropySynchronizeRequest: to.curare.compose.database.model.NegentropySynchronizeRequest,
        synchronizationRelayURLs: List<String>,
        activeKeyPair: KeyPair
    ) {
        // Under the same lock as the other overloads. storeNostrEvent reads the event, then
        // writes it and its indexes; two of those interleaving is a lost update. This path
        // used to skip the lock, which was survivable only while a single queue was the one
        // thing writing — a live subscription writing alongside a backfill is not that.
        storeNostrEventMutex.withLock {
            logger.d("saveNostrEvent: $nostrEvent")

            try {
                database.nostrDao().storeNostrEvent(
                    nostrEvent,
                    relayURL = negentropySynchronizeRequest.relayURL,
                    synchronizationRelayURLs = synchronizationRelayURLs,
                    level = negentropySynchronizeRequest.level,
                    activeKeyPair = activeKeyPair
                )

                database.negentropySynchronizeRequestDao().upsert(
                    negentropySynchronizeRequest.copy(
                        status = "processed"
                    )
                )
            } catch (e: Throwable) {
                logger.e("Failed to save nostr event ${nostrEvent.id}", e)
            }
        }
    }

    override suspend fun saveNostrEvent(
        nostrEvent: NostrEvent,
        relayURL: String,
        synchronizationRelayURLs: List<String>,
        level: Int,
        activeKeyPair: KeyPair
    ) {
        storeNostrEventMutex.withLock {
            logger.d("saveNostrEvent (live): $nostrEvent")

            try {
                database.nostrDao().storeNostrEvent(
                    nostrEvent,
                    relayURL = relayURL,
                    synchronizationRelayURLs = synchronizationRelayURLs,
                    level = level,
                    activeKeyPair = activeKeyPair
                )
            } catch (e: Throwable) {
                logger.e("Failed to save nostr event ${nostrEvent.id}", e)
            }
        }
    }

    override suspend fun queueSynchronizeNostrEvent(
        synchronizeNostrEventRequests: List<to.curare.compose.database.model.SynchronizeNostrEventRequest>,
    ) {
        database.synchronizeNostrEventRequestDao().insert(
            synchronizeNostrEventRequests
        )
    }

    override suspend fun queueNegentropySynchronizeRequest(negentropySynchronizeRequests: List<to.curare.compose.database.model.NegentropySynchronizeRequest>) {
        database.negentropySynchronizeRequestDao().insert(
            negentropySynchronizeRequests
        )
    }

    override suspend fun observeSynchronizeNostrEventRequestByPurposeAndStatusCount(purpose: String): Flow<Int> {
        return database.synchronizeNostrEventRequestDao().observeSynchronizeNostrEventRequestByPurposeAndStatusCount(
            purpose,
            listOf("pending", "processing")
        )
    }

    override suspend fun getNostrEvent(publicKey: HexKey, kind: Kind): NostrEvent? {
        return database.nostrEventDao().getNostrEventByPublicKeyAndKind(
            publicKey = publicKey,
            kind = kind
        )
    }

    override suspend fun observeNostrFeed(): Flow<List<to.curare.compose.database.model.intermdiate.LocalNostrEvent>> {
        return database.nostrEventDao().observeNostrEvents(
            kinds = arrayOf(
                TextNoteEvent.KIND,
                RepostEvent.KIND,
                ReactionEvent.KIND,
                LnZapEvent.KIND
            )
        )
    }

    override suspend fun observeNostrFeed(synchronizationFilter: SynchronizationFilter): Flow<List<to.curare.compose.database.model.intermdiate.LocalNostrEvent>> {
        return when {
            synchronizationFilter.kinds != null && synchronizationFilter.search != null -> {
                logger.d("observeFilteredNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeFilteredNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    search = synchronizationFilter.search
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.ids != null -> {
                logger.d("observeFilteredNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeFilteredNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    ids = synchronizationFilter.ids
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.authors != null -> {
                logger.d("observeAuthoredNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeAuthoredNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    authors = synchronizationFilter.authors
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.since != null &&  synchronizationFilter.tags?.contains("e") == true && synchronizationFilter.tags["e"]?.first()?.isNotEmpty() == true -> {
                logger.d("observePublicKeyMentionedNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeNostrEventReplies(
                    kinds = synchronizationFilter.kinds,
                    eventId = synchronizationFilter.tags["e"]?.first()!!,
                    since = synchronizationFilter.since
                )
            }
            synchronizationFilter.kinds != null && synchronizationFilter.tags?.contains("p") == true && synchronizationFilter.tags["p"]?.first()?.isNotEmpty() == true -> {
                logger.d("observePublicKeyMentionedNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observePublicKeyMentionedNostrEvents(
                    kinds = synchronizationFilter.kinds,
                    publicKey = synchronizationFilter.tags["p"]?.first()!!
                )
            }
            else -> {
                logger.d("observeNostrEvents: $synchronizationFilter")
                database.nostrEventDao().observeNostrEvents(
                    kinds = arrayOf(
                        TextNoteEvent.KIND,
                    )
                )
            }
        }
    }

    override suspend fun getNegentropicNostrFeedIds(
        synchronizationFilters: Array<SynchronizationFilter>,
        applyLimits: Boolean
    ): List<NostrEvent> {
        logger.d("getNegentropicNostrFeedIds: ${synchronizationFilters.contentToString()}")

        // A negentropy session reconciles against the union of its filters. Returning nothing
        // for anything other than a single filter (as this used to) is the worst possible
        // answer: an empty local set tells the relay we have none of these events, so it
        // hands back its entire set as ids to download.
        return synchronizationFilters
            .flatMap { synchronizationFilter ->
                matchNegentropicNostrEvents(synchronizationFilter, applyLimits)
            }
            .distinctBy { it.id }
    }

    private suspend fun matchNegentropicNostrEvents(
        synchronizationFilter: SynchronizationFilter,
        applyLimits: Boolean,
    ): List<NostrEvent> {
        val limit = if (applyLimits) {
            synchronizationFilter.limit ?: DEFAULT_NEGENTROPY_LIMIT
        } else {
            NostrEventFilterQuery.NO_LIMIT
        }

        val chatRoomIds = synchronizationFilter.marmotGroupChatRoomIds()
        if (chatRoomIds != null) {
            // Group messages live in their own table, which carries the NIP-40 expiry a relay
            // uses to decide whether it still serves the event, and an indexed chatRoomId
            // rather than a scan of the tags JSON.
            logger.d("getMarmotGroupEvents($applyLimits): $synchronizationFilter")
            return database.nostrEventDao().getMarmotGroupEvents(
                chatRoomIds = chatRoomIds,
                since = synchronizationFilter.since ?: GENESIS_AT,
                until = synchronizationFilter.until ?: Instant.DISTANT_FUTURE,
                limit = limit,
            ).map { marmotGroupEvent ->
                // Only the id and createdAt reach the negentropy vector; the rest is filled in
                // so callers keep a NostrEvent-shaped result.
                NostrEvent(
                    id = marmotGroupEvent.id,
                    content = marmotGroupEvent.encryptedContent,
                    kind = GroupEvent.KIND,
                    tags = arrayOf(arrayOf("h", marmotGroupEvent.chatRoomId)),
                    createdAt = marmotGroupEvent.createdAt,
                    sig = marmotGroupEvent.signature,
                    pubKey = marmotGroupEvent.publicKey
                )
            }
        }

        logger.d("getNostrEventsMatchingFilter($applyLimits): $synchronizationFilter")
        return database.nostrEventDao().getNostrEventsMatchingFilter(
            NostrEventFilterQuery.build(
                filter = synchronizationFilter,
                limit = limit,
            )
        )
    }

    /**
     * The chat room ids of a filter that asks for nothing but Marmot group messages, or null if
     * the filter says anything the [NostrEventDao.getMarmotGroupEvents] lookup cannot express.
     * Kept deliberately narrow: the branch it guards answers from a different table, so a filter
     * it only partly understands would silently drop the rest of the constraints — which is
     * exactly what the shape-guessing chain this replaced did.
     */
    private fun SynchronizationFilter.marmotGroupChatRoomIds(): Array<HexKey>? {
        val tags = tags ?: return null
        val rooms = tags["h"] ?: return null

        val onlyGroupEvents = kinds != null && kinds.size == 1 && kinds.first() == GroupEvent.KIND
        val nothingElseAsked = ids == null &&
            authors == null &&
            search == null &&
            tagsAll.isNullOrEmpty() &&
            tags.keys == setOf("h")

        return if (onlyGroupEvents && nothingElseAsked) rooms.toTypedArray() else null
    }

    override suspend fun observeLocalNostrEventById(nostrEventId: String): Flow<to.curare.compose.database.model.intermdiate.LocalNostrEvent?> {
        return database.nostrEventDao().observeNostrEventById(nostrEventId)
    }

    override suspend fun searchableProfiles(excludingPublicKeys: Array<HexKey>): List<to.curare.compose.database.model.Profile> {
        return database.profileDao().getAllProfilesExcludingOnesWithThesePublicKeys(
            excludedPublicKeys = excludingPublicKeys
        )
    }

    override suspend fun filterProfiles(searchTerm: String): List<to.curare.compose.database.model.intermdiate.LocalProfile> {
        return database.profileDao().filterProfiles(searchTerm = searchTerm)
    }


    override suspend fun observeProfileWithFollowers(publicKey: String): Flow<to.curare.compose.database.model.intermdiate.LocalProfileWithFollowers?> {
        return database.profileDao().observeProfileWithFollowersByPublicKey(publicKey)
    }

    override suspend fun observeProfileWithFollowing(publicKey: String): Flow<to.curare.compose.database.model.intermdiate.LocalProfileWithFollowing?> {
        return database.profileDao().observeProfileWithFollowingByPublicKey(publicKey)
    }

    override suspend fun getRecentSearches(): List<to.curare.compose.database.model.RecentSearch> {
        return database.recentSearchDao().getAllRecentSearches()
    }

    override suspend fun removeRecentSearch(recentSearch: to.curare.compose.database.model.RecentSearch) {
        database.recentSearchDao().deleteRecentSearch(recentSearch)
    }

    override suspend fun saveSearchQuery(
        query: String,
        synchronizationFilterArray: to.curare.compose.database.model.typealiases.SynchronizationFilterArray?
    ) {
        database.recentSearchDao().upsert(
            to.curare.compose.database.model.RecentSearch(
                query = query,
                synchronizationFilters = synchronizationFilterArray
            )
        )
    }

    override suspend fun observePublicKeyRelays(publicKey: String): Flow<List<to.curare.compose.database.model.Relay>> {
        return database.relayDao().observePublicKeyRelays(publicKey)
    }

    override suspend fun rescheduleBroadcastNostrEventRequests(broadcastNostrEventRequests: List<BroadcastNostrEventRequest>) {
        database.nostrDao().rescheduleBroadcastNostrEventRequests(
            broadcastNostrEventRequests
        )
    }
}
