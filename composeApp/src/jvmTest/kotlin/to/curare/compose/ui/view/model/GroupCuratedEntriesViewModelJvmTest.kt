package to.curare.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.exceptions.NostrPublishException
import to.curare.compose.network.dto.RelayDTO
import to.curare.compose.network.relays.EventPublishTransport
import to.curare.compose.network.relays.NostrPublishResult
import to.curare.compose.network.sockets.NostrIncomingMessage
import to.curare.compose.nostr.GroupCuratedEntry
import to.curare.compose.nostr.GroupRelay
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.GroupSignedWorkFixtures
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.entry
import to.curare.compose.ui.view.model.BroadcastGroupSignedEventViewModel.RelayOutcome
import to.curare.compose.ui.view.model.GroupCuratedEntriesViewModel.BroadcastAllProgress
import to.curare.compose.ui.view.model.GroupCuratedEntriesViewModel.EntryBroadcast
import to.curare.compose.ui.view.state.GroupCuratedEntriesUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What "Broadcast all" decides that nothing else can check.
 *
 * **Where each entry goes.** The same relays its own broadcast screen would
 * have filled in -- the group's General list plus the list the entry is in,
 * minus what the group has blocked -- because the button is the member
 * trusting that seed rather than editing it, and a rule that sent to
 * anywhere else would make the two buttons disagree.
 *
 * **One after another, in the order shown.** The transport is asked once per
 * entry, and the next is not asked until the last has settled.
 *
 * **What the answers become.** The count under each card and the sum above
 * them, with a relay that refused named against its reason, and an entry with
 * nowhere to go marked so rather than sent to nobody.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupCuratedEntriesViewModelJvmTest {

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")

    /**
     * Answers every event with [answers] and remembers each send in the order
     * it was asked for. Holds every answer back until [gate] is completed,
     * where a test hands one in, so a batch can be caught while it is out;
     * [arrived] says when the first send has reached it.
     */
    private class FakeTransport(
        private val gate: CompletableDeferred<Unit>? = null,
        private val answers: (NostrEvent) -> List<NostrPublishResult>,
    ) : EventPublishTransport {
        val sends = mutableListOf<Pair<NostrEvent, Set<RelayDTO>>>()

        val arrived = CompletableDeferred<Unit>()

        override suspend fun publishEvent(
            nostrEvent: NostrEvent,
            relays: Set<RelayDTO>
        ): Flow<NostrPublishResult> {
            sends += nostrEvent to relays
            arrived.complete(Unit)
            gate?.await()
            return flowOf(*answers(nostrEvent).toTypedArray())
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

    private val newest = entry("The Rise and Rise of Bitcoin", 1_700_000_900)
    private val oldest = entry("Magic Money", 1_700_000_300, suggestedBy = null)

    /**
     * A General list of [two] agreed, so each entry has one relay of the
     * group's and one of its list's ([one], which the fixture's schema names),
     * plus whatever [blocked] takes away.
     */
    private fun relayLists(blocked: List<NormalizedRelayUrl> = emptyList()): List<GroupRelayList> =
        GroupRelayList.allAmong(emptyList(), chatRoomId).map { list ->
            when (list.set) {
                GroupRelaySet.General -> GroupRelayList(
                    set = list.set,
                    signedEvent = GroupSignedWorkFixtures.relaySignedEvent(list.set),
                    relays = listOf(GroupRelay(two)),
                )
                GroupRelaySet.Blocked -> GroupRelayList(
                    set = list.set,
                    signedEvent = GroupSignedWorkFixtures.relaySignedEvent(list.set),
                    relays = blocked.map { GroupRelay(it) },
                )
                else -> list
            }
        }

    private fun viewModel(
        transport: EventPublishTransport,
        entries: List<GroupCuratedEntry> = listOf(newest, oldest),
        relayLists: List<GroupRelayList> = relayLists(),
    ) = GroupCuratedEntriesViewModel(
        chatRoomId = chatRoomId,
        initialGroupCuratedEntriesUIState = GroupCuratedEntriesUIState.Loaded(
            localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
            groupCuratedEntries = entries,
            relayLists = relayLists,
        ),
        chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
        publishTransport = transport,
    )

    private fun ok(relay: NormalizedRelayUrl, event: NostrEvent) = NostrPublishResult(
        result = NostrIncomingMessage.OkMessage(eventId = event.id, success = true),
        relayUrl = relay.url,
    )

    private fun rejected(relay: NormalizedRelayUrl, event: NostrEvent, reason: String) = NostrPublishResult(
        result = NostrIncomingMessage.OkMessage(eventId = event.id, success = false, message = reason),
        error = NostrPublishException(message = reason),
        relayUrl = relay.url,
    )

    private fun GroupCuratedEntriesViewModel.broadcastAllAndWait() {
        val job = broadcastAll()
        runBlocking { withTimeout(5_000) { job!!.join() } }
    }

    // -- where each entry goes ----------------------------------------------------

    @Test
    fun `each entry goes where its own broadcast would, one after another, in the order shown`() {
        val transport = FakeTransport { event -> listOf(ok(two, event), ok(one, event)) }
        val viewModel = viewModel(transport)

        viewModel.broadcastAllAndWait()

        // The newest first, as the screen lists them, and nothing twice.
        assertEquals(
            listOf(newest.signedEvent.id, oldest.signedEvent.id),
            transport.sends.map { it.first.id },
        )

        // The group's General relay and the list's own, asked as write relays
        // -- the pool would otherwise read from them too -- and what went was
        // the event as the group signed it.
        transport.sends.forEach { (event, relays) ->
            assertEquals(
                setOf(two, one).map { RelayDTO(url = it.url, read = false, write = true) }.toSet(),
                relays,
            )
            assertEquals("f".repeat(128), event.sig)
            assertEquals(chatRoomId, event.pubKey)
        }
    }

    @Test
    fun `a relay the group has blocked is left out, and an entry with nowhere to go is marked so`() {
        val transport = FakeTransport { event -> listOf(ok(two, event)) }

        // Everything the seed would name is blocked, so nothing is asked.
        val nowhere = viewModel(transport, relayLists = relayLists(blocked = listOf(one, two)))
        nowhere.broadcastAllAndWait()

        assertTrue(transport.sends.isEmpty(), "an entry with nowhere to go was sent somewhere")
        assertEquals(EntryBroadcast.NoRelays, nowhere.entryBroadcasts[newest.signedEvent.id])
        assertEquals(EntryBroadcast.NoRelays, nowhere.entryBroadcasts[oldest.signedEvent.id])
        assertEquals(
            BroadcastAllProgress.Done(acceptedEverywhere = 0, total = 2),
            nowhere.broadcastAllProgress,
        )

        // Only the list's relay blocked: the group's own is still asked.
        val partly = viewModel(transport, relayLists = relayLists(blocked = listOf(one)))
        partly.broadcastAllAndWait()

        assertEquals(2, transport.sends.size)
        transport.sends.forEach { (_, relays) ->
            assertEquals(setOf(two.url), relays.map { it.url }.toSet())
        }
    }

    // -- what the answers become ---------------------------------------------------

    @Test
    fun `each entry's count sits under it, a refusal is named with its reason, and the sum counts only the entries accepted everywhere`() {
        val transport = FakeTransport { event ->
            if (event.id == newest.signedEvent.id) {
                listOf(ok(two, event), ok(one, event))
            } else {
                listOf(ok(two, event), rejected(one, event, "blocked: not on the allow list"))
            }
        }
        val viewModel = viewModel(transport)

        viewModel.broadcastAllAndWait()

        val first = assertIs<EntryBroadcast.Sent>(viewModel.entryBroadcasts[newest.signedEvent.id])
        assertEquals(2, first.accepted)
        assertEquals(2, first.asked)
        assertTrue(first.isAcceptedEverywhere)
        assertEquals(emptyList(), first.refusals)

        val second = assertIs<EntryBroadcast.Sent>(viewModel.entryBroadcasts[oldest.signedEvent.id])
        assertEquals(1, second.accepted)
        assertEquals(2, second.asked)
        assertFalse(second.isAcceptedEverywhere)
        assertEquals(
            listOf(one to RelayOutcome.Rejected("blocked: not on the allow list")),
            second.refusals,
        )

        assertEquals(
            BroadcastAllProgress.Done(acceptedEverywhere = 1, total = 2),
            viewModel.broadcastAllProgress,
        )
        assertFalse(viewModel.isActionPending.value)
    }

    @Test
    fun `a relay that never answers counts against the entry, which is not the same as refused`() {
        // The pool answers for one relay and then the flow ends, which is what
        // a send that ran out of time looks like from here.
        val transport = FakeTransport { event -> listOf(ok(two, event)) }
        val viewModel = viewModel(transport, entries = listOf(newest))

        viewModel.broadcastAllAndWait()

        val sent = assertIs<EntryBroadcast.Sent>(viewModel.entryBroadcasts[newest.signedEvent.id])
        assertEquals(listOf(one to RelayOutcome.NoAnswer), sent.refusals)
        assertEquals(
            BroadcastAllProgress.Done(acceptedEverywhere = 0, total = 1),
            viewModel.broadcastAllProgress,
        )
    }

    // -- when nothing is sent ----------------------------------------------------------

    @Test
    fun `the next entry waits for the last to settle, and a second press while one is out is refused`() {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport(gate) { event -> listOf(ok(two, event), ok(one, event)) }
        val viewModel = viewModel(transport)

        // The first press is held at the transport, so the second meets the
        // guard however fast the dispatcher is.
        val first = viewModel.broadcastAll()
        val second = viewModel.broadcastAll()
        assertNull(second)
        assertTrue(viewModel.isActionPending.value)

        // One entry out and held; the other has not been asked for, and the
        // held one is marked as out while the rest are marked as nothing.
        runBlocking { withTimeout(5_000) { transport.arrived.await() } }
        assertEquals(listOf(newest.signedEvent.id), transport.sends.map { it.first.id })
        assertEquals(EntryBroadcast.Sending, viewModel.entryBroadcasts[newest.signedEvent.id])
        assertNull(viewModel.entryBroadcasts[oldest.signedEvent.id])
        assertEquals(BroadcastAllProgress.Sending(entry = 1, total = 2), viewModel.broadcastAllProgress)

        gate.complete(Unit)
        runBlocking { withTimeout(5_000) { first!!.join() } }

        assertEquals(2, transport.sends.size)
        assertFalse(viewModel.isActionPending.value)
    }

    @Test
    fun `nothing is sent from a screen with no entries, or one that has not loaded`() {
        val transport = FakeTransport { event -> listOf(ok(two, event)) }

        assertNull(viewModel(transport, entries = emptyList()).broadcastAll())

        val loading = GroupCuratedEntriesViewModel(
            chatRoomId = chatRoomId,
            initialGroupCuratedEntriesUIState = GroupCuratedEntriesUIState.Loading,
            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
            publishTransport = transport,
        )
        assertNull(loading.broadcastAll())

        assertTrue(transport.sends.isEmpty())
    }
}
