package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import to.curare.compose.database.model.NegentropySynchronizeRequest
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.SynchronizeNostrEventRequest
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.LocalCanSign
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.ProfilePreviewUIState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The person behind a pasted npub on their own screen: what is drawn in each state,
 * what the button hands over, and who is offered it.
 */
@OptIn(ExperimentalTestApi::class)
class ProfilePreviewScreenJvmTest {

    private val activeUserPublicKey = "a".repeat(64)
    private val profilePublicKey = "b".repeat(64)
    private val npub = profilePublicKey.hexToNpubHrp()

    private val alice = Profile(
        publicKey = profilePublicKey,
        displayName = "Alice",
        nip05 = "alice@example.com",
        about = "Translating hard books into Sesotho, one chapter at a time.",
        nostrEventId = "kind0",
    )

    @Test
    fun `a found profile is drawn whole, with the npub that was pasted`() = render(
        ProfilePreviewUIState.Loaded(profile = alice)
    ) {
        onNodeWithText("Alice").assertIsDisplayed()
        onNodeWithText("alice@example.com").assertIsDisplayed()
        onNodeWithText("Translating hard books into Sesotho, one chapter at a time.").assertIsDisplayed()
        onNodeWithText(npub).assertIsDisplayed()
    }

    @Test
    fun `starting a chat hands over the route the dialog used to push, and creates nothing here`() {
        var opened: Route? = null

        render(ProfilePreviewUIState.Loaded(profile = alice), onStartChat = { opened = it }) {
            onNodeWithText("Start new chat", useUnmergedTree = true).assertIsDisplayed().performClick()
        }

        assertEquals(
            ChatRoomMessagingRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = profilePublicKey,
                relayHint = null,
            ),
            opened,
        )
    }

    /** A message is a gift wrap sealed with the sender's key; a read-only identity is shown the person and nothing to do. */
    @Test
    fun `a read-only identity sees the person and no button`() = render(
        ProfilePreviewUIState.Loaded(profile = alice),
        canSign = false,
    ) {
        onNodeWithText("Alice").assertIsDisplayed()
        onNodeWithText("Start new chat", useUnmergedTree = true).assertDoesNotExist()
    }

    /** Arriving loading is the one case that runs `initiate`, so this render is given relays that never answer. */
    @Test
    fun `while the relays are asked the npub is already on screen`() = render(
        ProfilePreviewUIState.Loading,
        nostrRepository = NothingArrives,
    ) {
        onNodeWithText(npub).assertIsDisplayed()
        onNodeWithText("Looking for this profile on the relays.").assertIsDisplayed()
        onNodeWithText("Start new chat", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `nothing found says so and offers to ask again`() = render(
        ProfilePreviewUIState.NotFound
    ) {
        onNodeWithText("We could not find a profile for this key on the relays we asked.").assertIsDisplayed()
        onNodeWithText("Try again").assertIsDisplayed()
        onNodeWithText("Start new chat", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a queue that refused is an error with a retry`() = render(
        ProfilePreviewUIState.Error
    ) {
        onNodeWithText("Something went wrong").assertIsDisplayed()
        onNodeWithText("Try again").assertIsDisplayed()
    }

    /** No row on the device, and asks that go nowhere: the screen stays loading for as long as it is looked at. */
    private val NothingArrives = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeProfileWithPublicKey(publicKey: String): Flow<Profile?> = flowOf(null)
        override suspend fun queueSynchronizeNostrEvent(synchronizeNostrEventRequests: List<SynchronizeNostrEventRequest>) = Unit
        override suspend fun queueNegentropySynchronizeRequest(negentropySynchronizeRequests: List<NegentropySynchronizeRequest>) = Unit
    }

    /**
     * The screen at a phone's width. The state is passed in, so the NO_OP repository is
     * never asked for anything: `initiate` only runs for a screen that arrives loading.
     */
    private fun render(
        uiState: ProfilePreviewUIState,
        canSign: Boolean = true,
        nostrRepository: NostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
        onStartChat: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 1200) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    CompositionLocalProvider(LocalCanSign provides canSign) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            ProfilePreviewScreen(
                                activeUserPublicKey = activeUserPublicKey,
                                profilePublicKey = profilePublicKey,
                                initialProfilePreviewUIState = uiState,
                                nostrRepository = nostrRepository,
                                onStartChat = onStartChat,
                                onNavigateBack = {},
                            )
                        }
                    }
                }
            }
        }

        assertions()
    }
}
