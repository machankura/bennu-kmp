package to.curare.compose.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalNostrEvent
import to.curare.compose.database.model.intermdiate.LocalProfileWithFollowing
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.DkgRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.widgets.LocalCanSign
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.ActiveProfileUIState
import to.curare.compose.ui.view.state.HomeScreenUIState
import kotlin.test.Test

/**
 * The inventory in Phase 5 of docs/npub-sign-in.md, as assertions.
 *
 * Every write entrance one tap from the three tabs has a row there, and a row without an
 * assertion is a row that will regress: the compiler cannot say whether the inventory is
 * complete, since `LocalCanSign` is read where a control is drawn and nothing forces a
 * control to read it. So each screen is composed twice, once as an identity that can
 * sign and once as one that cannot, and the controls are looked for by their text.
 *
 * The repositories are the no-op ones with the reads each screen makes delegated to a
 * fixed answer, as `ChatPaneLayoutJvmTest` does.
 */
@OptIn(ExperimentalTestApi::class)
class ReadOnlyEntrancesJvmTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"
    private val eventId = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"

    // --- Home: the room list, or the empty state with the upgrade ---

    @Test
    fun `an identity that can sign is offered a new chat, and no upgrade`() = runDesktopComposeUiTest(400, 900) {
        setContent { AsIdentity(canSign = true) { Home() } }

        onNodeWithText("New chat", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sign in with the nsec", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a read-only identity is offered the upgrade, and no new chat`() = runDesktopComposeUiTest(400, 900) {
        setContent { AsIdentity(canSign = false) { Home() } }

        onNodeWithText("Sign in with the nsec", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("New chat", useUnmergedTree = true).assertDoesNotExist()
    }

    /** One column at every width: there is no list to put a detail beside. */
    @Test
    fun `a read-only identity in a large window still has one column`() = runDesktopComposeUiTest(1400, 900) {
        setContent { AsIdentity(canSign = false) { Home() } }

        onNodeWithText("Sign in with the nsec", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("New chat", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Pick a conversation to read it here.", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Profile: key packages and key recovery, or neither ---

    @Test
    fun `an identity that can sign is offered key recovery and key packages`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = true) { ActiveProfile() } }

        onNodeWithText("Key recovery", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Key package management", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sign out", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a read-only identity is offered neither, and can still sign out`() = runDesktopComposeUiTest(400, 1200) {
        setContent { AsIdentity(canSign = false) { ActiveProfile() } }

        onNodeWithText("Key recovery", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Key package management", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Sign out", useUnmergedTree = true).assertIsDisplayed()
    }

    // --- harness ---

    @Composable
    private fun AsIdentity(canSign: Boolean, content: @Composable () -> Unit) {
        CurareTheme {
            ProvideSnackbarHost {
                CompositionLocalProvider(LocalCanSign provides canSign) {
                    content()
                }
            }
        }
    }

    @Composable
    private fun Home() {
        HomeScreen(
            activeUserPublicKey = publicKey,
            initialHomeScreenUIState = HomeScreenUIState.Loaded(profileWithFollowing = profile),
            onNavigateToRoute = {},
            onNavigateToDirectMessageDetail = {},
            onNavigateToChatRoomCreation = {},
            nostrRepository = FixedProfile,
            chatRepository = NoRooms,
            frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
            dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
        )
    }

    @Composable
    private fun ActiveProfile() {
        ActiveProfileScreen(
            initialActiveProfileUIState = ActiveProfileUIState.Loaded(
                localNostrEvent = LocalNostrEvent(nostrEvent = metadataEvent, profile = profile.profile),
            ),
            activeUserPublicKey = publicKey,
            nostrEventId = eventId,
            onNavigateBack = {},
            onNavigateToRoute = {},
            nostrRepository = FixedProfile,
        )
    }

    private val metadataEvent = NostrEvent(
        id = eventId,
        pubKey = publicKey,
        content = """{"name":"Reader"}""",
        tags = emptyArray(),
        sig = "",
        kind = MetadataEvent.KIND,
    )

    private val profile = LocalProfileWithFollowing(
        nostrEvent = NostrEvent(
            id = eventId,
            pubKey = publicKey,
            content = "",
            tags = emptyArray(),
            sig = "",
            kind = TextNoteEvent.KIND,
        ),
        profile = Profile(
            publicKey = publicKey,
            displayName = "Reader",
            nostrEventId = eventId,
        ),
        following = emptyList(),
    )

    private val FixedProfile = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeProfileWithFollowing(
            publicKey: String,
        ): Flow<LocalProfileWithFollowing?> = flowOf(profile)

        override suspend fun observeLocalNostrEventById(
            nostrEventId: String,
        ): Flow<LocalNostrEvent?> = flowOf(LocalNostrEvent(nostrEvent = metadataEvent, profile = profile.profile))
    }

    private val NoRooms = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun observeChatRoomListByPublicKey(
            publicKey: String,
        ): Flow<List<LocalChatRoom>> = flowOf(emptyList())
    }
}
