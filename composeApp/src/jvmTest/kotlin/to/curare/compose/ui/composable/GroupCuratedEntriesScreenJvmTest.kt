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
import to.curare.compose.nostr.GroupCuratedEntry
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
 * most recently signed first, each with its broadcast and nothing else --
 * there is no way in to add one here.
 */
@OptIn(ExperimentalTestApi::class)
class GroupCuratedEntriesScreenJvmTest {

    private val newest = entry("The Rise and Rise of Bitcoin", 1_700_000_900)
    private val oldest = entry("Magic Money", 1_700_000_300, suggestedBy = null)

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
        // schemas screen, so this screen has no button of its own.
        onAllNodesWithText("Add entry").assertCountEquals(0)
        onAllNodesWithText("Broadcast").assertCountEquals(0)
    }

    private fun state(entries: List<GroupCuratedEntry>) = GroupCuratedEntriesUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupCuratedEntries = entries
    )

    private fun render(
        uiState: GroupCuratedEntriesUIState,
        onNavigateToRoute: (Route) -> Unit = {},
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
