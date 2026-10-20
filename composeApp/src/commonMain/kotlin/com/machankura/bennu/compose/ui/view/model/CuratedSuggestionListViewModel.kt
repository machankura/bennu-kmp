package com.machankura.bennu.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.machankura.bennu.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.bennu.compose.nostr.CuratedSuggestion
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.GroupRelayList
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.NostrRepository
import com.machankura.bennu.compose.ui.view.state.CuratedSuggestionListUIState
import kotlin.time.Duration.Companion.seconds

/**
 * The queue of one list the group curates, read off the relays the schema
 * names.
 *
 * A pull, the way every one-off read in this app is: a request row per relay
 * for the sync pump to drain, and a watch on the local table for what comes
 * back. Nothing here talks to a relay itself. What the screen shows is
 * therefore what this device has *stored* for the coordinate, which is also
 * what it shows with no network at all -- and what it showed last time, until
 * the relays answer again.
 *
 * ### Where the queue is read from
 *
 * The schema's `relay` tags, first. They are signed into the schema for exactly
 * this: a client that finds the list anywhere knows where its replies live and
 * cannot be sent elsewhere by an unsigned config. A schema that names none --
 * the NIP allows it and says a client may then pick its own -- is read from
 * the group's general relay list, which is where the group said it publishes,
 * and failing that from this build's default. The screen says which.
 *
 * ### What "still looking" means
 *
 * The pump marks a request sent when it goes out and processed when an event
 * comes back, and a relay holding nothing for the coordinate answers with an
 * EOSE the pump does not record. So there is no signal for "asked and empty",
 * and the screen would otherwise call every list empty for the second before
 * its rows arrived. [ASK_TIMEOUT] stands in: the queue is being asked until
 * something lands or the wait runs out, and after that an empty queue is
 * empty. The reference client does the same with a six-second timer.
 */
class CuratedSuggestionListViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    /** The `d` of the schema whose queue this is. */
    val identifier: String,
    initialCuratedSuggestionListUIState: CuratedSuggestionListUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
): ViewModel() {

    var curatedSuggestionListUIState: CuratedSuggestionListUIState by mutableStateOf(
        initialCuratedSuggestionListUIState
    )
        private set

    private val logger = Logger.withTag(TAG)

    /**
     * Serialises the read-modify-write on the state. The watch, the timer and
     * a re-ask each update one field of it from their own coroutine, and a copy
     * taken by one and written after another's is a lost update.
     */
    private val stateMutex = Mutex()

    private var askTimeout: Job? = null

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                curatedSuggestionListUIState = CuratedSuggestionListUIState.Error("Couldn't find the group")
                return@launch
            }

            // Opened on a schema this device does not hold: nothing to ask for,
            // since the coordinate is the group's key and the identifier and
            // half of that came from a screen that no longer agrees.
            val schema = chatRepository.groupCuratedSchemas(chatRoomId).firstOrNull { it.identifier == identifier }
            if (schema == null) {
                curatedSuggestionListUIState = CuratedSuggestionListUIState.Error("Couldn't find that schema")
                return@launch
            }

            val relays = relaysToAsk(
                schema = schema,
                general = chatRepository.groupRelayLists(chatRoomId).firstOrNull { it.set == GroupRelaySet.General },
            )

            curatedSuggestionListUIState = CuratedSuggestionListUIState.Loaded(
                localChatRoom = localChatRoom,
                schema = schema,
                relays = relays,
                isAsking = true,
            )

            ask(schema, relays)
            watch(schema)
        }
    }

    /**
     * Asks the relays again, for a member who believes something is missing.
     *
     * The one thing the watch does not do on its own: it shows what arrives,
     * and this is how more is made to arrive. Idempotent on the stored rows --
     * an event already held is upserted, not duplicated.
     */
    fun askAgain() {
        val loaded = curatedSuggestionListUIState as? CuratedSuggestionListUIState.Loaded ?: return

        viewModelScope.launch(Dispatchers.IO) {
            update { it.copy(isAsking = true) }
            ask(loaded.schema, loaded.relays)
        }
    }

    private suspend fun ask(schema: GroupCuratedSchema, relays: List<String>) {
        logger.d("Asking ${relays.size} relay(s) for the queue of ${schema.coordinate()}")

        nostrRepository.queueSynchronizeNostrEvent(requestsFor(schema, relays))

        askTimeout?.cancel()
        askTimeout = viewModelScope.launch(Dispatchers.IO) {
            delay(ASK_TIMEOUT)
            update { it.copy(isAsking = false) }
        }
    }

    /**
     * Watches the stored rows for the coordinate for as long as the screen
     * lives, re-reading the queue whole on every change. Whole rather than
     * incrementally because the reading is a merge -- newest per coordinate,
     * curated by identifier -- and a merge over a delta is a different reading.
     */
    private suspend fun watch(schema: GroupCuratedSchema) {
        nostrRepository.observeNostrEventsMatching(schema.queueReadFilter())
            .distinctUntilChanged()
            .collect { events ->
                val queue = CuratedSuggestion.queueOf(schema, events)
                logger.d("${queue.size} suggestion(s) to ${schema.coordinate()} among ${events.size} stored event(s)")

                update { loaded -> withQueue(loaded, queue) }
            }
    }

    private suspend fun update(
        change: (CuratedSuggestionListUIState.Loaded) -> CuratedSuggestionListUIState.Loaded
    ) {
        stateMutex.withLock {
            val loaded = curatedSuggestionListUIState as? CuratedSuggestionListUIState.Loaded ?: return
            curatedSuggestionListUIState = change(loaded)
        }
    }

    companion object {
        private const val TAG = "CuratedSuggestionListViewModel"

        /**
         * The schema's relays, else the group's, else the default. See the
         * class note for why in that order. Normalised so that two spellings
         * of one relay are one request, and only the group's *read* relays,
         * since this is a read.
         *
         * [general] is the group's general relay list as the screen has it, or
         * null in a room that has none of that to say. An agreed-but-empty
         * list falls through to the default rather than asking nobody: the
         * group withdrew where it publishes, not where its lists are read.
         */
        internal fun relaysToAsk(schema: GroupCuratedSchema, general: GroupRelayList?): List<String> {
            val named = schema.schema.relays.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }.distinct()
            if (named.isNotEmpty()) return named

            val relays = general?.takeIf { it.isAgreed }?.relays ?: GroupRelaySet.General.defaults()

            return relays.filter { it.read }.map { it.url.url }.distinct()
                .ifEmpty { GroupRelaySet.General.defaults().map { it.url.url } }
        }

        /** One request per relay, each carrying the NIP's two queries for the coordinate. */
        internal fun requestsFor(schema: GroupCuratedSchema, relays: List<String>): List<SynchronizeNostrEventRequest> =
            relays.map { relayUrl ->
                SynchronizeNostrEventRequest(
                    purpose = PURPOSE,
                    synchronizationFilters = schema.queueFilters(),
                    relayURL = relayUrl,
                    level = 0,
                )
            }

        /**
         * The state once [queue] has been read off the stored rows. Something
         * arriving means the relays have answered, so the asking ends there;
         * nothing arriving leaves it to the timer.
         */
        internal fun withQueue(
            loaded: CuratedSuggestionListUIState.Loaded,
            queue: List<CuratedSuggestion>,
        ): CuratedSuggestionListUIState.Loaded = loaded.copy(
            suggestions = queue,
            isAsking = loaded.isAsking && queue.isEmpty(),
        )

        /** The purpose the sync rows are filed under, for anything that counts them. */
        const val PURPOSE = "curated-queue"

        /**
         * How long an empty queue is "being asked" before it is called empty.
         * Longer than the reference client's six seconds because the request
         * queues behind the pump's four subscription slots before it goes out.
         */
        internal val ASK_TIMEOUT = 10.seconds

        fun factory(
            chatRoomId: String,
            activeUserPublicKey: HexKey,
            relayHint: String?,
            identifier: String,
            initialCuratedSuggestionListUIState: CuratedSuggestionListUIState = CuratedSuggestionListUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CuratedSuggestionListViewModel(
                    chatRoomId = chatRoomId,
                    activeUserPublicKey = activeUserPublicKey,
                    relayHint = relayHint,
                    identifier = identifier,
                    initialCuratedSuggestionListUIState = initialCuratedSuggestionListUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
