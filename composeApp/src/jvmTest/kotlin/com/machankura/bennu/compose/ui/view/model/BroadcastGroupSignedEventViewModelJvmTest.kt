package com.machankura.bennu.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.exceptions.NostrPublishException
import com.machankura.bennu.compose.network.dto.RelayDTO
import com.machankura.bennu.compose.network.relays.EventPublishTransport
import com.machankura.bennu.compose.network.relays.NostrPublishResult
import com.machankura.bennu.compose.network.sockets.NostrIncomingMessage
import com.machankura.bennu.compose.nostr.GroupRelay
import com.machankura.bennu.compose.nostr.GroupRelayList
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.nostr.Relays
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedEntryEvent
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.ui.view.model.BroadcastGroupSignedEventViewModel.RelayOutcome
import com.machankura.bennu.compose.ui.view.state.BroadcastGroupSignedEventUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The three decisions the broadcast screen makes that nothing else can check.
 *
 * **Where an event goes before anyone says otherwise.** The seeded list is what a
 * member who reads nothing and presses the button sends to, so it has to be the
 * group's own answer where the group has given one, has to leave out what the
 * group has blocked, and has to widen for the two kinds whose readers would not
 * otherwise find them.
 *
 * **What each relay's answer becomes.** The pool answers once per relay in three
 * shapes -- an OK, a rejection wrapped in one exception type, and any other error
 * -- and the rows show them as three different things because a member can act
 * on "blocked: not on the allow list" and cannot act on a red icon.
 *
 * **What goes on the wire.** The seven fields the group hashed and nothing else,
 * so a relay checking the id and the signature finds the event the group signed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastGroupSignedEventViewModelJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")
    private val three = RelayUrlNormalizer.normalize("wss://relay.three.example")

    /** Answers each relay with whatever [answers] says, keyed by host, and remembers what it was handed. */
    private class FakeTransport(
        private val answers: Map<String, NostrPublishResult>
    ) : EventPublishTransport {
        var sent: NostrEvent? = null
        var askedRelays: Set<RelayDTO> = emptySet()

        override suspend fun publishEvent(
            nostrEvent: NostrEvent,
            relays: Set<RelayDTO>
        ): Flow<NostrPublishResult> {
            sent = nostrEvent
            askedRelays = relays
            return flowOf(*answers.values.toTypedArray())
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(transport: EventPublishTransport = FakeTransport(emptyMap())) =
        BroadcastGroupSignedEventViewModel(
            chatRoomId = chatRoomId,
            signedEventId = "b".repeat(64),
            activeUserPublicKey = "a".repeat(64),
            relayHint = null,
            initialBroadcastGroupSignedEventUIState = BroadcastGroupSignedEventUIState.Loading,
            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
            publishTransport = transport,
        )

    private fun signedEvent(kind: Int = TextNoteEvent.KIND, tags: Array<Array<String>> = emptyArray()) =
        GroupSignedEvent(
            id = "b".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = kind,
            tags = tags,
            content = "The first chapter is out.",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        )

    private fun relaySignedEvent(set: GroupRelaySet) = GroupSignedEvent(
        id = "a" + set.kind.toString().padStart(5, '0') + "0".repeat(58),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = set.kind,
        tags = emptyArray(),
        content = "",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    /** Every set unsigned, which is a group that has never said where it lives. */
    private fun nothingAgreed() = GroupRelayList.allAmong(emptyList(), chatRoomId)

    private fun agreed(vararg lists: Pair<GroupRelaySet, List<GroupRelay>>) =
        nothingAgreed().map { list ->
            lists.firstOrNull { it.first == list.set }
                ?.let { GroupRelayList(list.set, relaySignedEvent(list.set), it.second) }
                ?: list
        }

    private fun ok(relay: NormalizedRelayUrl) = NostrPublishResult(
        result = NostrIncomingMessage.OkMessage(eventId = "b".repeat(64), success = true),
        relayUrl = relay.url,
    )

    private fun rejected(relay: NormalizedRelayUrl, reason: String) = NostrPublishResult(
        result = NostrIncomingMessage.OkMessage(eventId = "b".repeat(64), success = false, message = reason),
        error = NostrPublishException(message = reason),
        relayUrl = relay.url,
    )

    private fun failed(relay: NormalizedRelayUrl, reason: String) = NostrPublishResult(
        error = IllegalStateException(reason),
        relayUrl = relay.url,
    )

    // -- where an event goes before anyone says otherwise ------------------------

    @Test
    fun `a group that has not said where it lives sends to the app's publish set`() {
        assertEquals(
            Relays.eventPublishRelaySet.toList(),
            BroadcastGroupSignedEventViewModel.defaultRelaysFor(nothingAgreed(), TextNoteEvent.KIND),
        )
    }

    @Test
    fun `a group's own general list wins, and only the relays it writes to`() {
        val relayLists = agreed(
            GroupRelaySet.General to listOf(
                GroupRelay(one),
                // Read-only: where the group looks, not where it puts things.
                GroupRelay(two, read = true, write = false),
            )
        )

        // The group's answer and nothing from the app's set beside it: a member
        // who edited the relays meant them, and padding the list would send the
        // group's post somewhere the group decided not to be.
        assertEquals(
            listOf(one),
            BroadcastGroupSignedEventViewModel.defaultRelaysFor(relayLists, TextNoteEvent.KIND),
        )
    }

    @Test
    fun `a blocked relay is dropped whatever the other lists say`() {
        val relayLists = agreed(
            GroupRelaySet.General to listOf(GroupRelay(one), GroupRelay(two)),
            GroupRelaySet.Blocked to listOf(GroupRelay(two)),
        )

        assertEquals(
            listOf(one),
            BroadcastGroupSignedEventViewModel.defaultRelaysFor(relayLists, TextNoteEvent.KIND),
        )
    }

    @Test
    fun `a relay list also goes to the indexers, since its readers do not yet know the relays it names`() {
        val relayLists = agreed(GroupRelaySet.General to listOf(GroupRelay(one)))

        val seeded = BroadcastGroupSignedEventViewModel.defaultRelaysFor(
            relayLists, AdvertisedRelayListEvent.KIND
        )

        assertEquals(one, seeded.first())
        Relays.DefaultIndexerRelayList.forEach { indexer ->
            assertTrue(indexer in seeded, "the relay list would not reach ${indexer.url}")
        }

        // And a post does not: nobody looks an indexer up for a kind 1.
        val post = BroadcastGroupSignedEventViewModel.defaultRelaysFor(relayLists, TextNoteEvent.KIND)
        Relays.DefaultIndexerRelayList.forEach { indexer ->
            assertFalse(indexer in post, "a post was sent to the indexer ${indexer.url}")
        }
    }

    @Test
    fun `a curated schema also goes to the relays it names for its own replies`() {
        val relayLists = agreed(GroupRelaySet.General to listOf(GroupRelay(one)))

        val seeded = BroadcastGroupSignedEventViewModel.defaultRelaysFor(
            relayLists = relayLists,
            kind = CuratedSchemaEvent.KIND,
            tags = arrayOf(
                arrayOf("d", "films"),
                arrayOf("relay", three.url),
            ),
            content = "Films worth translating.",
        )

        // The group's relay first, then the list's own -- a client reading
        // suggestions on the list's relay finds the schema they answer.
        assertEquals(listOf(one, three), seeded)
    }

    @Test
    fun `an entry into a curated list goes to the list's relays, found by the coordinate it replies to`() {
        val relayLists = agreed(GroupRelaySet.General to listOf(GroupRelay(one)))

        val films = GroupCuratedSchema(
            signedEvent = relaySignedEvent(GroupRelaySet.General).copy(kind = CuratedSchemaEvent.KIND),
            schema = CuratedSchema(
                identifier = "films",
                title = "Film suggestion",
                name = "Films worth translating",
                description = "Films the group would subtitle.",
                visibility = CuratedVisibility.Public,
                relays = listOf(three.url),
            ).normalized(),
        )
        val books = films.copy(schema = films.schema.copy(identifier = "books", relays = listOf(two.url)))

        val seeded = BroadcastGroupSignedEventViewModel.defaultRelaysFor(
            relayLists = relayLists,
            kind = CuratedEntryEvent.CANONICAL_KIND,
            tags = arrayOf(
                arrayOf("d", "magic-money"),
                arrayOf("title", "Magic Money"),
                arrayOf("a", films.coordinate(), "", "root"),
            ),
            content = "",
            schemas = listOf(books, films),
        )

        // The group's relay, then the relays of the list replied to -- and not
        // the other list's: the NIP sends an entry where its own schema says.
        assertEquals(listOf(one, three), seeded)

        // An entry whose schema this device does not hold goes where the group
        // publishes, since there is nothing else to go on.
        assertEquals(
            listOf(one),
            BroadcastGroupSignedEventViewModel.defaultRelaysFor(
                relayLists = relayLists,
                kind = CuratedEntryEvent.CANONICAL_KIND,
                tags = arrayOf(arrayOf("a", "31889:${"0".repeat(64)}:elsewhere", "", "root")),
                schemas = listOf(books, films),
            )
        )
    }

    @Test
    fun `seeding happens once, so an emptied list stays empty`() {
        val viewModel = viewModel()
        val relayLists = agreed(GroupRelaySet.General to listOf(GroupRelay(one)))
        val event = signedEvent()

        viewModel.seedRelays(relayLists, event.kind, event.tags, event.content)
        viewModel.seedRelays(relayLists, event.kind, event.tags, event.content)
        assertEquals(listOf(one), viewModel.relays.toList())

        viewModel.removeRelay(one)
        viewModel.seedRelays(relayLists, event.kind, event.tags, event.content)
        assertEquals(emptyList(), viewModel.relays.toList())
    }

    // -- what may be added ----------------------------------------------------------

    @Test
    fun `an add is refused by name, and a good one clears the refusal`() {
        val viewModel = viewModel()

        assertFalse(viewModel.addRelay("https://not-a-relay.example"))
        assertEquals(BroadcastGroupSignedEventViewModel.AddFailure.NotARelay, viewModel.addFailure)

        assertTrue(viewModel.addRelay(one.url))
        assertNull(viewModel.addFailure)

        assertFalse(viewModel.addRelay(one.url))
        assertEquals(BroadcastGroupSignedEventViewModel.AddFailure.AlreadyListed, viewModel.addFailure)

        assertEquals(listOf(one), viewModel.relays.toList())
    }

    // -- what each relay's answer becomes ---------------------------------------------

    @Test
    fun `each relay's answer lands on its own row, and the sum counts only the OKs`() {
        val transport = FakeTransport(
            mapOf(
                "one" to ok(one),
                "two" to rejected(two, "blocked: not on the allow list"),
                "three" to failed(three, "Connection refused"),
            )
        )
        val viewModel = viewModel(transport)
        listOf(one, two, three).forEach { assertTrue(viewModel.addRelay(it.url)) }

        var toldNoRelays = false
        val job = viewModel.broadcast(signedEvent(), onNoRelays = { toldNoRelays = true })
        runBlocking { withTimeout(5_000) { job!!.join() } }

        assertFalse(toldNoRelays)
        assertEquals(RelayOutcome.Accepted, viewModel.outcomes[one])
        assertEquals(RelayOutcome.Rejected("blocked: not on the allow list"), viewModel.outcomes[two])
        assertEquals(RelayOutcome.Failed("Connection refused"), viewModel.outcomes[three])
        assertEquals(
            BroadcastGroupSignedEventViewModel.BroadcastSummary(accepted = 1, asked = 3),
            viewModel.lastBroadcast,
        )
        assertFalse(viewModel.isActionPending.value)

        // Asked as write relays: the pool would otherwise read from them too.
        assertEquals(
            setOf(one, two, three).map { RelayDTO(url = it.url, read = false, write = true) }.toSet(),
            transport.askedRelays,
        )
    }

    @Test
    fun `an answer is matched by host, since the pool may spell the relay with a trailing slash`() {
        val transport = FakeTransport(
            mapOf("one" to ok(one).copy(relayUrl = one.url.trimEnd('/') + "/"))
        )
        val viewModel = viewModel(transport)
        assertTrue(viewModel.addRelay(one.url))

        val job = viewModel.broadcast(signedEvent(), onNoRelays = {})
        runBlocking { withTimeout(5_000) { job!!.join() } }

        assertEquals(RelayOutcome.Accepted, viewModel.outcomes[one])
    }

    @Test
    fun `a relay that never answers is marked so, which is not the same as refused`() {
        // The pool answers for one relay and then the flow ends, which is what a
        // send that ran out of time looks like from here.
        val transport = FakeTransport(mapOf("one" to ok(one)))
        val viewModel = viewModel(transport)
        assertTrue(viewModel.addRelay(one.url))
        assertTrue(viewModel.addRelay(two.url))

        val job = viewModel.broadcast(signedEvent(), onNoRelays = {})
        runBlocking { withTimeout(5_000) { job!!.join() } }

        assertEquals(RelayOutcome.Accepted, viewModel.outcomes[one])
        assertEquals(RelayOutcome.NoAnswer, viewModel.outcomes[two])
        assertEquals(
            BroadcastGroupSignedEventViewModel.BroadcastSummary(accepted = 1, asked = 2),
            viewModel.lastBroadcast,
        )
    }

    @Test
    fun `an empty list asks nobody and says so`() {
        val transport = FakeTransport(emptyMap())
        val viewModel = viewModel(transport)

        var toldNoRelays = false
        val job = viewModel.broadcast(signedEvent(), onNoRelays = { toldNoRelays = true })

        assertNull(job)
        assertTrue(toldNoRelays)
        assertNull(transport.sent)
        assertFalse(viewModel.isActionPending.value)
    }

    // -- what goes on the wire ------------------------------------------------------------

    @Test
    fun `what goes on the wire is the event the group signed, field for field`() {
        val event = signedEvent(
            kind = MetadataEvent.KIND,
            tags = arrayOf(arrayOf("alt", "The group's profile")),
        )

        val wire = BroadcastGroupSignedEventViewModel.wireEventOf(event).toNostrJsonObject()

        assertEquals(event.id, wire["id"]!!.jsonPrimitive.content)
        assertEquals(event.publicKey, wire["pubkey"]!!.jsonPrimitive.content)
        assertEquals(event.createdAt.epochSeconds.toString(), wire["created_at"]!!.jsonPrimitive.content)
        assertEquals(event.kind.toString(), wire["kind"]!!.jsonPrimitive.content)
        assertEquals(event.content, wire["content"]!!.jsonPrimitive.content)
        assertEquals(event.signature, wire["sig"]!!.jsonPrimitive.content)
        assertEquals("""[["alt","The group's profile"]]""", wire["tags"].toString())
    }

    // -- what never goes at all ------------------------------------------------

    @Test
    fun `the group's wallet statement is never handed to a relay`() {
        val transport = FakeTransport(mapOf(one.url to ok(one)))

        // The xpub on a relay is every address and the whole balance, to anyone.
        // A refusal nobody tested is the one that disappears in a refactor of
        // what the broadcast list accepts.
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                BroadcastGroupSignedEventViewModel.sendToRelays(
                    publishTransport = transport,
                    signedEvent = signedEvent(kind = GroupWalletStatementEvent.KIND),
                    targets = listOf(one),
                )
            }
        }
        assertNull(transport.sent, "nothing reached the transport")
    }
}
