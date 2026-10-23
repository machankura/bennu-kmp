package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.NostrRepository
import com.machankura.bennu.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.bennu.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.state.ChatRoomDetailUIState
import com.machankura.bennu.compose.wallet.GroupWalletRollout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The row on the group's screen that stands for its signed work, and what it
 * opens.
 *
 * The group's nostr profile, relay lists and posts had rows here, and a paste
 * for proposing any of them; they are gone, and the wallet is what is left.
 */
@OptIn(ExperimentalTestApi::class)
class GroupSignedWorkRowsJvmTest {

    @Test
    fun `the wallet row sits under the signing key and above the subgroups`() = render(state()) {
        if (!GroupWalletRollout.ENABLED) return@render

        val signingKey = onNodeWithText("Signing key").getBoundsInRoot().top
        val wallet = onNodeWithText("Group onchain wallet").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(signingKey < wallet, "the wallet row climbed above the signing key row")
        assertTrue(wallet < subgroups, "the wallet row fell below the group's subgroups")
    }

    @Test
    fun `the wallet row opens the wallet`() {
        if (!GroupWalletRollout.ENABLED) return

        var opened: Route? = null

        render(state(), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Not opened yet").assertIsDisplayed()
            onNodeWithText("Group onchain wallet").performClick()
        }

        assertEquals(GroupWalletRoute(activeUserPublicKey, chatRoomId, relayHint = null), opened)
    }

    @Test
    fun `the group's screen has no nostr profile, relays, posts or paste`() = render(state()) {
        onNodeWithText("Nostr profile").assertDoesNotExist()
        onNodeWithText("Relays").assertDoesNotExist()
        onNodeWithText("Posts").assertDoesNotExist()
        onNodeWithText("Propose event").assertDoesNotExist()

        // The rest of the screen is there, so the rows' absence is an absence
        // rather than a screen that failed to draw.
        onNodeWithText("Subgroups").assertIsDisplayed()
    }

    @Test
    fun `a NIP-17 room has no key to sign with and so no wallet row`() = render(
        state(mlsGroupState = null)
    ) {
        onNodeWithText("Group onchain wallet").assertDoesNotExist()
        onNodeWithText("Subgroups").assertIsDisplayed()
    }

    private fun state(mlsGroupState: String? = "state") = ChatRoomDetailUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(mlsGroupState),
        groupKeyState = GroupSignedWorkFixtures.groupKeyState,
    )

    /**
     * The screen at a phone's width and a window tall enough to compose the whole
     * of it, since a `LazyColumn` does not lay out what it would not show.
     *
     * The state is passed in rather than loaded, so the NO_OP repositories are
     * never asked for anything: `initiateChatRoomDetail` only runs for a screen
     * that arrives in [ChatRoomDetailUIState.Loading].
     */
    private fun render(
        uiState: ChatRoomDetailUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            BennuTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            onNavigateToRoute = onNavigateToRoute,
                            onPopBackToRoute = {},
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        assertions()
    }
}
