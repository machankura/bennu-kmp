package to.curare.compose.ui.composable

import to.curare.compose.nostr.MarmotProfile
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import to.curare.compose.database.model.MarmotKeyPackage
import to.curare.compose.database.model.NegentropySynchronizeRequest
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.SynchronizeNostrEventRequest
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.repository.ChatRepository
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.LocalCanSign
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.ProfilePreviewUIState
import to.curare.compose.ui.view.state.ProfilePreviewUIState.Readiness
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
        ProfilePreviewUIState.Loaded(profile = alice, readiness = Readiness.CanStartChat)
    ) {
        onNodeWithText("Alice").assertIsDisplayed()
        onNodeWithText("alice@example.com").assertIsDisplayed()
        onNodeWithText("Translating hard books into Sesotho, one chapter at a time.").assertIsDisplayed()
        onNodeWithText(npub).assertIsDisplayed()
    }

    @Test
    fun `starting a chat hands over the route the dialog used to push, and creates nothing here`() {
        var opened: Route? = null

        render(ProfilePreviewUIState.Loaded(profile = alice, readiness = Readiness.CanStartChat), onStartChat = { opened = it }) {
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
        ProfilePreviewUIState.Loaded(profile = alice, readiness = Readiness.CanStartChat),
        canSign = false,
    ) {
        onNodeWithText("Alice").assertIsDisplayed()
        onNodeWithText("Start new chat", useUnmergedTree = true).assertDoesNotExist()
    }

    /** Arriving loading is the one case that runs `initiate`, so this render is given relays that never answer. */
    // --- whether the button can be pressed ---

    @Test
    fun `a room that exists is opened, not started again`() {
        var opened: Route? = null

        render(
            ProfilePreviewUIState.Loaded(
                profile = alice,
                readiness = Readiness.ExistingChat(chatRoomId = "room", relayHint = "wss://hint.example"),
            ),
            onStartChat = { opened = it },
        ) {
            onNodeWithText("You already have a chat with Alice.").assertIsDisplayed()
            onNodeWithText("Start new chat", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("Open chat", useUnmergedTree = true).assertIsDisplayed().performClick()
        }

        assertEquals(
            ChatRoomMessagingRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = "room", relayHint = "wss://hint.example"),
            opened,
        )
    }

    @Test
    fun `while the key package is still owed the button waits, and says so`() = render(
        ProfilePreviewUIState.Loaded(profile = alice, readiness = Readiness.Checking)
    ) {
        onNodeWithText("Start new chat", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Start new chat").assertIsNotEnabled()
        onNodeWithText("Checking whether Alice can receive messages here.").assertIsDisplayed()
    }

    @Test
    fun `a person not yet on Curare cannot be written to, and the reason is under the button`() = render(
        ProfilePreviewUIState.Loaded(profile = alice, readiness = Readiness.NotYetOnCurare)
    ) {
        onNodeWithText("Start new chat").assertIsNotEnabled()
        onNodeWithText("Alice hasn't set up messaging on Curare yet. Try again later, or ask them to open Curare.").assertIsDisplayed()
        onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun `a person whose key package is here can be written to at once`() = render(
        ProfilePreviewUIState.Loaded(profile = alice, readiness = Readiness.CanStartChat)
    ) {
        onNodeWithText("Start new chat").assertIsEnabled()
        onNodeWithText("Checking whether", substring = true).assertDoesNotExist()
        onNodeWithText("hasn't set up messaging", substring = true).assertDoesNotExist()
    }

    // --- the other states ---

    @Test
    fun `while the relays are asked the npub is already on screen`() = render(
        ProfilePreviewUIState.Loading,
        nostrRepository = NothingArrives,
        chatRepository = NoRooms,
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

    /** No room with the person, and no key package for them. */
    private val NoRooms = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun observeMarmotKeyPackageForPublicKey(publicKey: String, roomProfile: MarmotProfile): Flow<MarmotKeyPackage?> = flowOf(null)
        override suspend fun findDirectMessageRoom(userPublicKey: String, peerPublicKey: String): LocalChatRoom? = null
    }

    /**
     * The screen at a phone's width. The state is passed in, so the NO_OP repository is
     * never asked for anything: `initiate` only runs for a screen that arrives loading.
     */
    private fun render(
        uiState: ProfilePreviewUIState,
        canSign: Boolean = true,
        nostrRepository: NostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
        chatRepository: ChatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
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
                                chatRepository = chatRepository,
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
