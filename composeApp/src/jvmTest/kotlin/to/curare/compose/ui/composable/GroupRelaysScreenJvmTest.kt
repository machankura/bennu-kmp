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
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.relaySignedEvent
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.signedRelayLists
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupRelaysRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.GroupRelaysUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The group's relay lists on their own screen: a row per set whatever the
 * group has said, a broadcast under each list it has agreed, and the editor
 * for a member holding a share.
 */
@OptIn(ExperimentalTestApi::class)
class GroupRelaysScreenJvmTest {

    @Test
    fun `every set gets a row, and the two absences read differently`() = render(state()) {
        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("relay.one.example").assertIsDisplayed()
        onNodeWithText("Messages").assertIsDisplayed()
        onNodeWithText("Search").assertIsDisplayed()
        onNodeWithText("Blocked").assertIsDisplayed()
        // Two of them, because Messages and Search are both unagreed -- and the
        // count is the assertion: a row per set, not a row per set the group has
        // got round to. The blocked list was agreed empty, which is a decision
        // and reads as one.
        onAllNodesWithText("Not set yet").assertCountEquals(2)
        onNodeWithText("No relays in this list").assertIsDisplayed()
    }

    @Test
    fun `every agreed list offers a broadcast under its row, the withdrawn one included`() {
        var opened: Route? = null

        render(state(), onNavigateToRoute = { opened = it }) {
            // The two the group has agreed -- the withdrawn one included, since a
            // withdrawal is a statement relays still holding the old list need to
            // hear -- and not the two it has never agreed, which are not events.
            val buttons = onAllNodesWithText("Broadcast")
            buttons.assertCountEquals(2)

            val general = onNodeWithText("General").getBoundsInRoot().top
            val blocked = onNodeWithText("Blocked").getBoundsInRoot().top
            val first = buttons[0].getBoundsInRoot().top
            val second = buttons[1].getBoundsInRoot().top

            assertTrue(general < first && first < blocked, "the general list's broadcast is not under its row")
            assertTrue(blocked < second, "the blocked list's broadcast is not under its row")

            buttons[1].performClick()
        }

        assertEquals(
            BroadcastGroupSignedEventRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                signedEventId = relaySignedEvent(GroupRelaySet.Blocked).id
            ),
            opened
        )
    }

    @Test
    fun `a member holding a share of the key is offered the editor`() {
        var opened: Route? = null

        render(state(), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Edit relays").assertIsDisplayed().performClick()
        }

        assertEquals(EditGroupRelaysRoute(activeUserPublicKey, chatRoomId, relayHint = null), opened)
    }

    @Test
    fun `a member holding no share of the key reads the relays and is offered no edit`() = render(
        state(canSign = false)
    ) {
        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("relay.one.example").assertIsDisplayed()
        onNodeWithText("Edit relays").assertDoesNotExist()
        onAllNodesWithText("Broadcast").assertCountEquals(2)
    }

    private fun state(canSign: Boolean = true) = GroupRelaysUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupRelayLists = signedRelayLists(),
        canSign = canSign
    )

    private fun render(
        uiState: GroupRelaysUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupRelaysScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialGroupRelaysUIState = uiState,
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
