package to.curare.compose.ui.view.model

import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.SynchronizeNostrEventRequest
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalNostrEvent
import to.curare.compose.database.model.types.SynchronizationFilter
import to.curare.compose.nostr.CuratedSuggestion
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupRelay
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.nostr.Relays
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.CuratedSuggestionListUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The decisions the queue screen's view model makes that nothing else can check.
 *
 * **Where the queue is read from.** The schema's relays, then the group's, then
 * the default -- and getting the order wrong is quiet, because a queue read
 * from the wrong relay is an empty queue with no error attached.
 *
 * **What is asked for.** One request per relay carrying the NIP's two queries,
 * and the local watch over the same rows, so that what comes back is what is
 * shown.
 *
 * **When looking stops.** Rows arriving end it; nothing arriving leaves it to
 * the timer. A screen that stopped looking on an empty first read of the local
 * table would call every list empty before its relays had been asked.
 */
class CuratedSuggestionListViewModelJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val ada = "a".repeat(64)

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")

    private fun schema(relays: List<String>) = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = "c".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        schema = CuratedSchema(
            identifier = "films",
            title = "Film suggestion",
            name = "Films worth translating",
            description = "Films the group would subtitle.",
            visibility = CuratedVisibility.Public,
            fields = listOf(CuratedField.IDENTIFIER, CuratedField.TITLE),
            relays = relays,
        ).normalized(),
    )

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = ada,
            subject = "Translation room",
            description = null,
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = "state"
        )
    )

    private fun general(relays: List<GroupRelay>, agreed: Boolean = true) = GroupRelayList(
        set = GroupRelaySet.General,
        signedEvent = if (agreed) {
            GroupSignedEvent(
                id = "a".repeat(64),
                chatRoomId = chatRoomId,
                publicKey = chatRoomId,
                kind = GroupRelaySet.General.kind,
                tags = emptyArray(),
                content = "",
                signature = "f".repeat(128),
                createdAt = Instant.fromEpochSeconds(1_700_000_000),
            )
        } else {
            null
        },
        relays = relays,
    )

    @Test
    fun `the schema's own relays come first, and two spellings of one are one`() {
        val asked = CuratedSuggestionListViewModel.relaysToAsk(
            schema(listOf("wss://relay.one.example", "wss://relay.two.example/", "wss://relay.one.example/")),
            general = general(listOf(GroupRelay(RelayUrlNormalizer.normalize("wss://relay.three.example")))),
        )

        assertEquals(listOf(one.url, two.url), asked)
        assertEquals(2, asked.size, "one relay spelled two ways was asked twice")
    }

    @Test
    fun `a schema naming no relays is read from where the group reads`() {
        val three = RelayUrlNormalizer.normalize("wss://relay.three.example")
        val four = RelayUrlNormalizer.normalize("wss://relay.four.example")

        // The group's read relays, and not its write-only one: this is a read.
        assertEquals(
            listOf(three.url),
            CuratedSuggestionListViewModel.relaysToAsk(
                schema(emptyList()),
                general = general(listOf(GroupRelay(three), GroupRelay(four, read = false, write = true))),
            )
        )

        // Nothing agreed, or agreed empty: the build's own relay rather than nobody.
        assertEquals(
            listOf(Relays.ephemeral.url),
            CuratedSuggestionListViewModel.relaysToAsk(schema(emptyList()), general = null)
        )
        assertEquals(
            listOf(Relays.ephemeral.url),
            CuratedSuggestionListViewModel.relaysToAsk(schema(emptyList()), general = general(emptyList()))
        )
        assertEquals(
            listOf(Relays.ephemeral.url),
            CuratedSuggestionListViewModel.relaysToAsk(
                schema(emptyList()),
                general = general(listOf(GroupRelay(three)), agreed = false)
            )
        )
    }

    @Test
    fun `one request per relay, carrying the NIP's two queries for the coordinate`() {
        val schema = schema(listOf(one.url))
        val requests = CuratedSuggestionListViewModel.requestsFor(schema, listOf(one.url, two.url))

        assertEquals(listOf(one.url, two.url), requests.map { it.relayURL })
        assertTrue(requests.all { it.purpose == CuratedSuggestionListViewModel.PURPOSE })

        val (suggestions, canonical) = requests.first().synchronizationFilters
        val coordinate = "31889:$chatRoomId:films"

        assertEquals(listOf(31888), suggestions.kinds?.toList())
        assertEquals(mapOf("a" to listOf(coordinate)), suggestions.tags)
        assertEquals(null, suggestions.authors, "suggestions are not scoped by author: anyone may make one")

        assertEquals(listOf(31890), canonical.kinds?.toList())
        assertEquals(listOf(chatRoomId), canonical.authors?.toList(), "canonical entries are the group's alone")
        assertEquals(mapOf("a" to listOf(coordinate)), canonical.tags)

        // And the watch covers both, so what is asked for is what is shown.
        val watched = schema.queueReadFilter()
        assertEquals(listOf(31888, 31890), watched.kinds?.toList())
        assertEquals(mapOf("a" to listOf(coordinate)), watched.tags)
    }

    @Test
    fun `rows arriving end the looking, and an empty read does not`() {
        val asking = CuratedSuggestionListUIState.Loaded(
            localChatRoom = localChatRoom,
            schema = schema(listOf(one.url)),
            relays = listOf(one.url),
            isAsking = true,
        )

        val stillEmpty = CuratedSuggestionListViewModel.withQueue(asking, emptyList())
        assertTrue(stillEmpty.isAsking, "an empty first read of the local table was taken as the relays' answer")

        val arrived = CuratedSuggestionListViewModel.withQueue(asking, listOf(row("Magic Money", 1_700_000_000)))
        assertFalse(arrived.isAsking)
        assertEquals(listOf("Magic Money"), arrived.suggestions.map { it.entry.title })

        // Once the timer has called it empty, a later arrival is still shown.
        val late = CuratedSuggestionListViewModel.withQueue(stillEmpty.copy(isAsking = false), listOf(row("Late", 1_700_000_900)))
        assertFalse(late.isAsking)
        assertEquals(listOf("Late"), late.suggestions.map { it.entry.title })
    }

    @Test
    fun `opening the screen asks the schema's relays and shows what the table holds for them`() {
        val schema = schema(listOf(one.url))
        val queued = mutableListOf<SynchronizeNostrEventRequest>()
        val watched = mutableListOf<SynchronizationFilter>()

        val stored = NostrEvent(
            id = "1".repeat(64),
            pubKey = ada,
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            tags = arrayOf(
                arrayOf("d", "magic-money"),
                arrayOf("title", "Magic Money"),
                arrayOf("a", schema.coordinate(), "", "root"),
            ),
            content = "",
            sig = "b".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        )

        val nostrRepository = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
            override suspend fun queueSynchronizeNostrEvent(synchronizeNostrEventRequests: List<SynchronizeNostrEventRequest>) {
                queued += synchronizeNostrEventRequests
            }

            override suspend fun observeNostrEventsMatching(synchronizationFilter: SynchronizationFilter): Flow<List<LocalNostrEvent>> {
                watched += synchronizationFilter
                return flowOf(listOf(LocalNostrEvent(nostrEvent = stored, profile = null)))
            }
        }

        val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? = localChatRoom
            override suspend fun groupCuratedSchemas(chatRoomId: String): List<GroupCuratedSchema> = listOf(schema)
            override suspend fun groupRelayLists(chatRoomId: String): List<GroupRelayList> = emptyList()
        }

        val viewModel = CuratedSuggestionListViewModel(
            chatRoomId = chatRoomId,
            activeUserPublicKey = ada,
            relayHint = null,
            identifier = "films",
            initialCuratedSuggestionListUIState = CuratedSuggestionListUIState.Loading,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
        )

        try {
            viewModel.initiate()

            // The read runs on IO and lands on the state when it lands; the
            // assertion waits for the rows rather than for a fixed time.
            val loaded = runBlocking {
                withTimeout(5_000) {
                    while (true) {
                        val state = viewModel.curatedSuggestionListUIState
                        if (state is CuratedSuggestionListUIState.Loaded && state.suggestions.isNotEmpty()) break
                        delay(20)
                    }
                    assertIs<CuratedSuggestionListUIState.Loaded>(viewModel.curatedSuggestionListUIState)
                }
            }

            assertEquals(listOf(one.url), loaded.relays)
            assertEquals(listOf(one.url), queued.map { it.relayURL })
            assertEquals(listOf(schema.queueReadFilter()), watched)
            assertEquals(listOf("Magic Money"), loaded.suggestions.map { it.entry.title })
            assertFalse(loaded.isAsking, "rows arrived and the screen is still looking")
        } finally {
            // The timer would otherwise run on for ten seconds after the test.
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `a schema this device does not hold is an error rather than an empty queue`() {
        val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? = localChatRoom
            override suspend fun groupCuratedSchemas(chatRoomId: String): List<GroupCuratedSchema> = emptyList()
        }

        val viewModel = CuratedSuggestionListViewModel(
            chatRoomId = chatRoomId,
            activeUserPublicKey = ada,
            relayHint = null,
            identifier = "films",
            initialCuratedSuggestionListUIState = CuratedSuggestionListUIState.Loading,
            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
            chatRepository = chatRepository,
        )

        try {
            viewModel.initiate()

            runBlocking {
                withTimeout(5_000) {
                    while (viewModel.curatedSuggestionListUIState == CuratedSuggestionListUIState.Loading) delay(20)
                }
            }

            assertEquals(
                CuratedSuggestionListUIState.Error("Couldn't find that schema"),
                viewModel.curatedSuggestionListUIState
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun row(title: String, createdAt: Long) = CuratedSuggestion(
        entry = CuratedEntry(
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            id = createdAt.toString().padStart(64, 'e'),
            pubKey = ada,
            createdAt = createdAt,
            identifier = title.lowercase().replace(' ', '-'),
            title = title,
            values = mapOf("title" to listOf(title)),
        ),
        event = NostrEvent(
            id = createdAt.toString().padStart(64, 'e'),
            pubKey = ada,
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            tags = emptyArray(),
            content = "",
            sig = "b".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt),
        ),
        suggester = null,
        isCurated = false,
    )
}
