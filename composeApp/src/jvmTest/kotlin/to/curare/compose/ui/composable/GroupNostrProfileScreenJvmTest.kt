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
import to.curare.compose.nostr.GroupNostrProfile
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.profile
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupNostrProfileRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.GroupNostrProfileUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The group's nostr profile on its own screen: what is drawn, what the
 * broadcast under it sends, and who is offered the editor.
 */
@OptIn(ExperimentalTestApi::class)
class GroupNostrProfileScreenJvmTest {

    @Test
    fun `the profile is drawn whole, with its broadcast under it`() {
        var opened: Route? = null

        render(state(groupNostrProfile = profile), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Translation collective").assertIsDisplayed()
            onNodeWithText("group@example.com").assertIsDisplayed()
            onNodeWithText("A group translating hard books into Sesotho.").assertIsDisplayed()
            onAllNodesWithText("Signed by the group on", substring = true).assertCountEquals(1)

            // One, under the card it sends, and it sends the profile's event.
            val buttons = onAllNodesWithText("Broadcast")
            buttons.assertCountEquals(1)
            val card = onNodeWithText("group@example.com").getBoundsInRoot().top
            assertTrue(card < buttons[0].getBoundsInRoot().top, "the broadcast is not under the card")

            buttons[0].performClick()
        }

        assertEquals(
            BroadcastGroupSignedEventRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                signedEventId = profile.signedEvent.id
            ),
            opened
        )
    }

    @Test
    fun `a member holding a share of the key is offered the editor`() {
        var opened: Route? = null

        render(state(groupNostrProfile = profile), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Edit Nostr profile").assertIsDisplayed().performClick()
        }

        assertEquals(
            EditGroupNostrProfileRoute(activeUserPublicKey, chatRoomId, relayHint = null),
            opened
        )
    }

    @Test
    fun `a member holding no share of the key reads the profile and is offered no edit`() = render(
        state(groupNostrProfile = profile, canSign = false)
    ) {
        // The profile is worth reading whoever is looking; proposing one needs a
        // share, and `proposeSigningBatch` throws without one. The broadcast
        // stays: the signature is the group's whoever repeats it.
        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
        onAllNodesWithText("Broadcast").assertCountEquals(1)
    }

    @Test
    fun `a group that has said nothing says so, and offers to say something`() = render(
        state(groupNostrProfile = null)
    ) {
        onNodeWithText("This group hasn't said anything about itself on Nostr yet.")
            .assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertIsDisplayed()

        // Nothing signed, so nothing to send.
        onAllNodesWithText("Broadcast").assertCountEquals(0)
    }

    @Test
    fun `a group that has said nothing offers no editor to a member holding no share`() = render(
        state(groupNostrProfile = null, canSign = false)
    ) {
        onNodeWithText("This group hasn't said anything about itself on Nostr yet.")
            .assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    private fun state(
        groupNostrProfile: GroupNostrProfile?,
        canSign: Boolean = true
    ) = GroupNostrProfileUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupNostrProfile = groupNostrProfile,
        canSign = canSign
    )

    /**
     * The screen at a phone's width. The state is passed in, so the NO_OP
     * repository is never asked for anything: `initiate` only runs for a screen
     * that arrives loading.
     */
    private fun render(
        uiState: GroupNostrProfileUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupNostrProfileScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialGroupNostrProfileUIState = uiState,
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
