package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.entry
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.GroupCuratedEntriesUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The entries the group has accepted on their own screen: one card per entry,
 * most recently signed first, each with its broadcast, and one button at the
 * bottom that broadcasts all of them -- there is no way in to add one here.
 */
@OptIn(ExperimentalTestApi::class)
class GroupCuratedEntriesScreenJvmTest {

    private val newest = entry("The Rise and Rise of Bitcoin", 1_700_000_900)
    private val oldest = entry("Magic Money", 1_700_000_300, suggestedBy = null)

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")

    /** A pool that answers each event as [answers] says and never opens a socket. */
    private class FakeTransport(
        private val answers: (NostrEvent) -> List<NostrPublishResult> = { emptyList() }
    ) : EventPublishTransport {
        override suspend fun publishEvent(
            nostrEvent: NostrEvent,
            relays: Set<RelayDTO>
        ): Flow<NostrPublishResult> = flowOf(*answers(nostrEvent).toTypedArray())
    }

    @Test
    fun `the entries are drawn most recently signed first, one card per entry`() = render(
        state(entries = listOf(newest, oldest))
    ) {
        val first = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot().top
        val second = onNodeWithText("Magic Money").getBoundsInRoot().top
        assertTrue(first < second, "the entries were drawn oldest first")

        // Which list, what the entry is -- labelled as the schema labels it --
        // and who suggested it, where somebody did.
        onAllNodesWithText("In Films worth translating").assertCountEquals(2)
        onAllNodesWithText("Year: 2014", substring = true).assertCountEquals(2)
        onAllNodesWithText("Suggested by eebb74ab · ", substring = true).assertCountEquals(1)
        // Dated like every signed statement the group makes, once per entry.
        onAllNodesWithText("Signed by the group on", substring = true).assertCountEquals(2)
    }

    @Test
    fun `every accepted entry offers a broadcast under its own card`() {
        var opened: Route? = null

        render(state(entries = listOf(newest, oldest)), onNavigateToRoute = { opened = it }) {
            // The entry is the one event the group signs whose broadcast the
            // queue screen depends on, since that is where the "Curated" mark is
            // read back from.
            val buttons = onAllNodesWithText("Broadcast")
            buttons.assertCountEquals(2)

            val firstCard = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot().top
            val secondCard = onNodeWithText("Magic Money").getBoundsInRoot().top
            val first = buttons[0].getBoundsInRoot().top
            val second = buttons[1].getBoundsInRoot().top

            assertTrue(firstCard < first && first < secondCard, "the first entry's broadcast is not under its card")
            assertTrue(secondCard < second, "the second entry's broadcast is not under its card")

            buttons[0].performClick()
        }

        assertEquals(
            BroadcastGroupSignedEventRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                signedEventId = newest.signedEvent.id
            ),
            opened
        )
    }

    @Test
    fun `a group that has accepted nothing says so, and offers no way to add one here`() = render(
        state(entries = emptyList())
    ) {
        onNodeWithText("This group hasn't accepted anything into a list yet.", substring = true)
            .assertIsDisplayed()

        // An entry is accepted from the queue on its schema's card, on the
        // schemas screen, so this screen has no button of its own -- and
        // nothing to broadcast, so no button for that either.
        onAllNodesWithText("Add entry").assertCountEquals(0)
        onAllNodesWithText("Broadcast").assertCountEquals(0)
        onAllNodesWithText("Broadcast all").assertCountEquals(0)
    }

    @Test
    fun `the button at the bottom sends every entry, and each card says what its relays answered`() {
        val ok = { relay: String, event: NostrEvent ->
            NostrPublishResult(
                result = NostrIncomingMessage.OkMessage(eventId = event.id, success = true),
                relayUrl = relay,
            )
        }
        val transport = FakeTransport { event ->
            if (event.id == newest.signedEvent.id) {
                listOf(ok(two.url, event), ok(one.url, event))
            } else {
                listOf(
                    ok(two.url, event),
                    NostrPublishResult(
                        result = NostrIncomingMessage.OkMessage(
                            eventId = event.id,
                            success = false,
                            message = "blocked: not on the allow list",
                        ),
                        error = NostrPublishException(message = "blocked: not on the allow list"),
                        relayUrl = one.url,
                    ),
                )
            }
        }

        render(state(entries = listOf(newest, oldest)), transport = transport) {
            onNodeWithText("Broadcast all").performClick()

            // The sum above the cards, once the last has settled.
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Accepted by every relay for 1 of 2 entries.")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            // Each entry's count, and the one relay that refused named with its
            // reason -- the relay that said OK is in the count and nowhere else.
            onNodeWithText("Accepted by 2 of 2 relays.").assertIsDisplayed()
            onNodeWithText("Accepted by 1 of 2 relays.").assertIsDisplayed()
            onNodeWithText("relay.one.example · Rejected: blocked: not on the allow list")
                .assertIsDisplayed()
            onAllNodesWithText("relay.two.example", substring = true).assertCountEquals(0)

            // The sum above every card; each answer under its own card and
            // above that card's broadcast, which is what a refusal calls for.
            val sum = onNodeWithText("Accepted by every relay for 1 of 2 entries.").getBoundsInRoot().top
            val firstCard = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot().top
            val firstAnswer = onNodeWithText("Accepted by 2 of 2 relays.").getBoundsInRoot().top
            val secondCard = onNodeWithText("Magic Money").getBoundsInRoot().top
            val secondAnswer = onNodeWithText("Accepted by 1 of 2 relays.").getBoundsInRoot().top
            val buttons = onAllNodesWithText("Broadcast")
            val firstButton = buttons[0].getBoundsInRoot().top
            val secondButton = buttons[1].getBoundsInRoot().top

            assertTrue(sum < firstCard, "the sum is not above the cards")
            assertTrue(firstCard < firstAnswer && firstAnswer < firstButton, "the first answer is not between its card and its broadcast")
            assertTrue(firstButton < secondCard, "the second card climbed above the first's broadcast")
            assertTrue(secondCard < secondAnswer && secondAnswer < secondButton, "the second answer is not between its card and its broadcast")
        }
    }

    /**
     * A General list of [two] agreed, so each entry goes to one relay of the
     * group's and one of its list's -- [one], which the fixture's schema names.
     */
    private fun relayLists(): List<GroupRelayList> =
        GroupRelayList.allAmong(emptyList(), chatRoomId).map { list ->
            if (list.set != GroupRelaySet.General) return@map list

            GroupRelayList(
                set = list.set,
                signedEvent = GroupSignedWorkFixtures.relaySignedEvent(list.set),
                relays = listOf(GroupRelay(two)),
            )
        }

    private fun state(entries: List<GroupCuratedEntry>) = GroupCuratedEntriesUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupCuratedEntries = entries,
        relayLists = relayLists(),
    )

    private fun render(
        uiState: GroupCuratedEntriesUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        transport: EventPublishTransport = FakeTransport(),
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupCuratedEntriesScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialGroupCuratedEntriesUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            publishTransport = transport,
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        assertions()
    }
}
