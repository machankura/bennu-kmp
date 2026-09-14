package to.curare.compose.repository

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import to.curare.compose.database.model.FrostSignerMessage
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalFrostSigningSession

/**
 * Reads, opens and answers FROST signing sessions.
 *
 * Advancing one is the inbound path's job, with one exception: a session waiting
 * on this device's owner does not advance until [approve] is called, because it
 * will not sign on their behalf until they say so.
 */
interface FrostSigningRepository {
    fun observeSessionById(sessionId: String): Flow<FrostSigningSession?>

    /**
     * Every session the room has run, newest first, each with what it signs.
     *
     * Unlike a ceremony, signing recurs -- and two sessions can be open at once,
     * which is why the proposal list shows all of them rather than the latest.
     */
    fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>>

    fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>>

    /**
     * Who holds a share of the key a ceremony made, and so can take part in a
     * session signing with it.
     *
     * The members who put a host key into the ceremony -- the same rows
     * `FrostSigningManager` places signer ids over, so this cannot disagree with
     * whose nonces, partial signatures and refusals the session counts. A
     * ceremony cannot complete without every participant's host key, so for a
     * completed one this is the whole participant set.
     *
     * Not the room's roster. A room can hold members who were added after its
     * ceremony and hold no share, and a subgroup's ceremony runs in the parent's
     * room, which holds everybody the subgroup is not. A screen listing the room
     * would show people the session is not waiting on and cannot be answered by.
     */
    fun observeShareHolders(dkgSessionId: String): Flow<Set<HexKey>>

    /**
     * The events a session signs, in the order its proposal fixed.
     *
     * Separate from the session because a session signs a batch, and what
     * separates one event of it from another is forced by FROST rather than
     * chosen — see [FrostSigningItem].
     */
    fun observeItems(sessionId: String): Flow<List<FrostSigningItem>>

    suspend fun getItems(sessionId: String): List<FrostSigningItem>

    suspend fun getSessionById(sessionId: String): FrostSigningSession?

    /** Whether this room holds a key it can sign with, so the UI offers nothing that would fail. */
    suspend fun canSign(chatRoomId: String): Boolean

    /**
     * Opens a session asking the group to sign an event with these fields. The
     * author is the room's own key, not the proposer's, and is filled in here --
     * the room's id, since signing runs at the path the room was derived at.
     */
    suspend fun proposeSigning(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String
    ): FrostSigningSession?

    /**
     * Opens one session over several events, so a group answers once instead of
     * once per event.
     *
     * Four group events and one approval whatever the size, but k independent
     * FROST instances underneath -- there is no such thing as one signature over
     * k messages, and no way to share a nonce between two of them. See
     * [FrostSigningItem].
     *
     * Two things a caller has to decide before reaching for this.
     *
     * **A batch is only as available as its worst item.** It is all-or-nothing:
     * if any event cannot be aggregated the session fails and none of them are
     * applied. Events that do not belong together should not travel together.
     *
     * **A retry is a new batch, never this one again.** Its items' nonce seeds
     * have already been published against an aggregate, and reusing one would
     * produce two partial signatures over a single secret nonce -- which is how
     * a share is extracted. Propose afresh; the manager mints new seeds by
     * construction.
     */
    suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<EventTemplate<*>>
    ): FrostSigningSession?

    /**
     * Opens a session over an event and the events that name it, for a batch
     * that is not a flat list: a chapter and its chunks, where each chunk
     * carries the chapter's id.
     *
     * That id cannot be worked out by a caller -- it is a hash over the group's
     * key at the room's derivation path, neither of which is resolved until the
     * proposal is made -- so [lead] is built first and handed to [dependents],
     * which returns the events referring to it. The lead is item 0 and so is
     * applied first, which is what anything holding a foreign key to it needs.
     *
     * Everything the flat form says still holds: all-or-nothing, only as
     * available as its worst item, and a retry is a new batch.
     */
    suspend fun proposeSigningBatch(
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        lead: EventTemplate<*>,
        dependents: (lead: Event) -> List<EventTemplate<*>>
    ): FrostSigningSession?

    /** Agrees to sign, letting the session publish this device's part and run on. */
    suspend fun approve(localChatRoom: LocalChatRoom, sessionId: String)

    /** Refuses, and says so, since a t-of-n group can proceed without this member. */
    suspend fun decline(localChatRoom: LocalChatRoom, sessionId: String)

    /**
     * The events a session was opened over, signature or no signature.
     *
     * What [signedEvents] gives back once the group has answered, and what it
     * refuses to give back before then. A caller wanting an id -- to open a
     * second session over events that name one of these -- needs it while the
     * first session is still running, and the id is settled at proposal time:
     * it is the hash the signers are putting their shares behind.
     */
    fun unsignedEvents(items: List<FrostSigningItem>): List<Event>

    /** The finished events, or empty while any of them is still running. */
    fun signedEvents(items: List<FrostSigningItem>): List<Event>

    companion object {
        val NO_OP_FROST_SIGNING_REPOSITORY: FrostSigningRepository = object : FrostSigningRepository {
            override fun observeSessionById(sessionId: String): Flow<FrostSigningSession?> = flowOf(null)

            override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>> =
                flowOf(emptyList())

            override fun observeMessages(sessionId: String): Flow<List<FrostSignerMessage>> =
                flowOf(emptyList())

            override fun observeShareHolders(dkgSessionId: String): Flow<Set<HexKey>> =
                flowOf(emptySet())

            override fun observeItems(sessionId: String): Flow<List<FrostSigningItem>> =
                flowOf(emptyList())

            override suspend fun getItems(sessionId: String): List<FrostSigningItem> = emptyList()

            override suspend fun getSessionById(sessionId: String): FrostSigningSession? = null

            override suspend fun canSign(chatRoomId: String): Boolean = false

            override suspend fun proposeSigning(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                kind: Kind,
                tags: Array<Array<String>>,
                content: String
            ): FrostSigningSession? = null

            override suspend fun proposeSigningBatch(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                events: List<EventTemplate<*>>
            ): FrostSigningSession? = null

            override suspend fun proposeSigningBatch(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                lead: EventTemplate<*>,
                dependents: (lead: Event) -> List<EventTemplate<*>>
            ): FrostSigningSession? = null

            override suspend fun approve(localChatRoom: LocalChatRoom, sessionId: String) = Unit

            override suspend fun decline(localChatRoom: LocalChatRoom, sessionId: String) = Unit

            override fun unsignedEvents(items: List<FrostSigningItem>): List<Event> = emptyList()

            override fun signedEvents(items: List<FrostSigningItem>): List<Event> = emptyList()
        }
    }
}
