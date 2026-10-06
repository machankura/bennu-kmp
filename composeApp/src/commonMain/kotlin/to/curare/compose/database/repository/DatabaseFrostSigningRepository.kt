package to.curare.compose.database.repository

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.model.FrostSignerMessage
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalFrostSigningSession
import to.curare.compose.managers.FrostSigningManager
import to.curare.compose.nostr.dkg.DkgRitualEvents
import to.curare.compose.repository.FrostSigningRepository

class DatabaseFrostSigningRepository(
    private val database: CurareDatabase,
    private val scope: CoroutineScope
): FrostSigningRepository {
    private val logger = Logger.withTag(TAG)

    override fun observeSessionById(sessionId: String): Flow<FrostSigningSession?> =
        database.frostSigningSessionDao().observeSessionById(sessionId)

    override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>> =
        database.frostSigningSessionDao().observeSessionsForChatRoom(chatRoomId)

    override fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>> =
        database.frostSigningSessionDao().observeMessages(sessionId)

    override fun observeShareHolders(dkgSessionId: String): Flow<Set<HexKey>> =
        database.dkgSessionDao().observeMessages(dkgSessionId).map { messages ->
            messages
                .filter { it.kind == DkgRitualEvents.HOST_KEY }
                .map { it.participantPublicKey }
                .toSet()
        }

    override fun observeItems(sessionId: String): Flow<List<FrostSigningItem>> =
        database.frostSigningSessionDao().observeItems(sessionId)

    override suspend fun getItems(sessionId: String): List<FrostSigningItem> =
        database.frostSigningSessionDao().getItems(sessionId)

    override suspend fun getSessionById(sessionId: String): FrostSigningSession? =
        database.frostSigningSessionDao().getSessionById(sessionId)

    override suspend fun canSign(chatRoomId: String): Boolean =
        FrostSigningManager.canSign(database, chatRoomId)

    override suspend fun proposeSigning(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSigning(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            kind = kind,
            tags = tags,
            content = content
        )
    }

    override suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<EventTemplate<*>>
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSigningBatch(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            events = events
        )
    }

    override suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        lead: EventTemplate<*>,
        dependents: (lead: Event) -> List<EventTemplate<*>>
    ): FrostSigningSession? = proposing(localChatRoom) {
        FrostSigningManager.proposeSigningBatch(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            lead = lead,
            dependents = dependents
        )
    }

    /**
     * Proposing throws when the group has no key, when this device was not in the
     * ceremony, or when a batch is empty or over the cap. All four are states the
     * UI is supposed to have checked for, so they become a null the caller
     * reports rather than a crash.
     */
    private inline fun proposing(
        localChatRoom: LocalChatRoom,
        propose: () -> FrostSigningSession
    ): FrostSigningSession? = try {
        propose()
    } catch (e: Throwable) {
        logger.e("Error proposing a signature in ${localChatRoom.chatRoom.id}", e)
        null
    }

    override suspend fun resend(localChatRoom: LocalChatRoom, sessionId: String): Boolean = try {
        FrostSigningManager.resend(
            database = database,
            localChatRoom = localChatRoom,
            sessionId = sessionId
        )
    } catch (e: Throwable) {
        // False rather than rethrown, for the reason `approve` swallows: a
        // resend is a repair, and a repair that takes the screen down with it is
        // worse than one that did not happen.
        logger.e("Error resending signing session $sessionId", e)
        false
    }

    override suspend fun approve(localChatRoom: LocalChatRoom, sessionId: String) {
        try {
            FrostSigningManager.approve(
                database = database,
                localChatRoom = localChatRoom,
                sessionId = sessionId
            )
        } catch (e: Throwable) {
            // The session fails itself and tells the group; swallowing here keeps a
            // protocol fault from taking the screen down with it.
            logger.e("Error approving signing session $sessionId", e)
        }
    }

    override suspend fun decline(localChatRoom: LocalChatRoom, sessionId: String) {
        try {
            FrostSigningManager.decline(
                database = database,
                localChatRoom = localChatRoom,
                sessionId = sessionId
            )
        } catch (e: Throwable) {
            logger.e("Error declining signing session $sessionId", e)
        }
    }

    override fun unsignedEvents(items: List<FrostSigningItem>): List<Event> =
        items.map { Event.fromJson(it.unsignedEventJson) }

    override fun signedEvents(items: List<FrostSigningItem>): List<Event> =
        FrostSigningManager.signedEvents(items)

    companion object {
        private const val TAG = "DatabaseFrostSigningRepository"
    }
}
