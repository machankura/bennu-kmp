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
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import to.curare.compose.nostr.GroupCuratedEntry
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupNostrProfile
import to.curare.compose.nostr.GroupPost
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.MantraRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.entry
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.post
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.profile
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.schema
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.signedRelayLists
import to.curare.compose.ui.composable.navigation.routes.GroupCuratedEntriesRoute
import to.curare.compose.ui.composable.navigation.routes.GroupCuratedSchemasRoute
import to.curare.compose.ui.composable.navigation.routes.GroupNostrProfileRoute
import to.curare.compose.ui.composable.navigation.routes.GroupPostsRoute
import to.curare.compose.ui.composable.navigation.routes.GroupRelaysRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.ChatRoomDetailUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five rows on the group's screen that stand for its signed work, and what
 * each one opens.
 *
 * The rows' contents need little testing -- a name and a count in a `ListItem`
 * -- but three things about them do, and none is settled by reading the screen
 * top to bottom.
 *
 * **Their place in the order.** They go under what the room *is* -- the key it
 * signs as and whose child it is -- and above what the room has *made*, because
 * the group's account of itself is part of its identity rather than part of its
 * work; and among themselves they read as a chain, from who the group says it is
 * to what it has decided. That ordering is an intention a later `item {}` in
 * the wrong place would silently undo, and the screen is long enough that
 * nobody would notice.
 *
 * **What they open.** A row is a promise about the screen behind it, and five
 * rows that look alike are five chances to wire one to the wrong route.
 *
 * **Their absence in a NIP-17 room.** A NIP-17 room's id is a conversation, not
 * a key it can sign as, so it has no nostr identity and never will. A row there
 * would promise something that is not coming, which is worse than saying
 * nothing.
 */
@OptIn(ExperimentalTestApi::class)
class GroupSignedWorkRowsJvmTest {

    @Test
    fun `the rows sit under the signing key and above the group's work, in the order the block reads`() =
        render(state(groupNostrProfile = profile)) {
            val signingKey = onNodeWithText("Signing key").getBoundsInRoot().top
            val nostrProfile = onNodeWithText("Nostr profile").getBoundsInRoot().top
            val relays = onNodeWithText("Relays").getBoundsInRoot().top
            val posts = onNodeWithText("Posts").getBoundsInRoot().top
            val schemas = onNodeWithText("Curated schemas").getBoundsInRoot().top
            val entries = onNodeWithText("Curated entries").getBoundsInRoot().top
            val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

            assertTrue(signingKey < nostrProfile, "the nostr profile row climbed above the signing key row")
            assertTrue(nostrProfile < relays, "the relays row climbed above the profile")
            assertTrue(relays < posts, "the posts row climbed above the relays")
            assertTrue(posts < schemas, "the curated schemas row climbed above the posts")
            assertTrue(schemas < entries, "the curated entries row climbed above the schemas")
            assertTrue(entries < subgroups, "the identity block fell below the group's work")
        }

    @Test
    fun `each row says what its screen holds`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            posts = listOf(post("The second thing", 1_700_000_900), post("The first thing", 1_700_000_000)),
            schemas = listOf(schema("films", "Films worth translating", 1_700_000_000))
        )
    ) {
        // The group's own name and address, not the room's subject: the row is
        // about what the rest of nostr sees.
        onNodeWithText("Translation collective · group@example.com").assertIsDisplayed()
        // Counted against the four sets, the withdrawn one included: an agreed
        // empty list is a decision, and the row counts decisions.
        onNodeWithText("2 of 4 lists agreed").assertIsDisplayed()
        onNodeWithText("2 posts").assertIsDisplayed()
        onNodeWithText("1 schema").assertIsDisplayed()
        onNodeWithText("No entries yet").assertIsDisplayed()
    }

    @Test
    fun `a group that has signed nothing has rows that say so`() = render(
        state(groupNostrProfile = null)
    ) {
        onNodeWithText("Not described yet").assertIsDisplayed()
        onNodeWithText("0 of 4 lists agreed").assertIsDisplayed()
        onNodeWithText("No posts yet").assertIsDisplayed()
        onNodeWithText("No schemas yet").assertIsDisplayed()
        onNodeWithText("No entries yet").assertIsDisplayed()
    }

    @Test
    fun `a profile with no name is not named after the key`() = render(
        state(
            groupNostrProfile = GroupNostrProfile(
                signedEvent = profile.signedEvent,
                metadata = UserMetadata()
            )
        )
    ) {
        // The signing key row already shows the key, and showing it twice would
        // say the group has a name when it has not.
        onNodeWithText("No name on this profile").assertIsDisplayed()
    }

    @Test
    fun `each row opens the screen for its section`() {
        val expected = mapOf(
            "Nostr profile" to GroupNostrProfileRoute(activeUserPublicKey, chatRoomId, relayHint = null),
            "Relays" to GroupRelaysRoute(activeUserPublicKey, chatRoomId, relayHint = null),
            "Posts" to GroupPostsRoute(activeUserPublicKey, chatRoomId, relayHint = null),
            "Curated schemas" to GroupCuratedSchemasRoute(activeUserPublicKey, chatRoomId, relayHint = null),
            "Curated entries" to GroupCuratedEntriesRoute(activeUserPublicKey, chatRoomId, relayHint = null),
        )

        expected.forEach { (row, route) ->
            var opened: Route? = null

            render(state(groupNostrProfile = profile), onNavigateToRoute = { opened = it }) {
                onNodeWithText(row).performClick()
            }

            assertEquals(route, opened, "the $row row opened the wrong screen")
        }
    }

    @Test
    fun `the contents stay off the group's screen`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            posts = listOf(post("Something the group said", 1_700_000_000)),
            schemas = listOf(schema("films", "Films worth translating", 1_700_000_000)),
            entries = listOf(entry("The Rise and Rise of Bitcoin", 1_700_000_900))
        )
    ) {
        // The rows summarise; the cards, their broadcasts and the gated ways in
        // are on the screens behind them. A card that stayed here would be the
        // old section under a new name.
        onNodeWithText("A group translating hard books into Sesotho.").assertDoesNotExist()
        onNodeWithText("relay.one.example").assertDoesNotExist()
        onNodeWithText("Something the group said").assertDoesNotExist()
        onNodeWithText("The Rise and Rise of Bitcoin").assertDoesNotExist()
        onAllNodesWithText("Broadcast").assertCountEquals(0)
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
        onNodeWithText("Edit relays").assertDoesNotExist()
        onNodeWithText("Add post").assertDoesNotExist()
        onNodeWithText("Add schema").assertDoesNotExist()
        onNodeWithText("View suggestions").assertDoesNotExist()
    }

    @Test
    fun `a member holding a share of the key may propose any event the rows can open`() = render(
        state(groupNostrProfile = profile)
    ) {
        // Last in the identity block: after the rows and before the divider,
        // because it is about the whole block rather than any one row of it.
        val entries = onNodeWithText("Curated entries").getBoundsInRoot().top
        val propose = onNodeWithText("Propose event").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(entries < propose, "the paste entry climbed above the rows")
        assertTrue(propose < subgroups, "the paste entry fell out of the identity block")
    }

    @Test
    fun `a member holding no share of the key is offered no paste`() = render(
        state(groupNostrProfile = profile, canEditNostrProfile = false)
    ) {
        // The same gate as every way in behind the rows: proposing is a signature
        // by the group, and `proposeSigning` throws for a device holding no share.
        // The rows themselves stay, because reading takes no share.
        onNodeWithText("Propose event").assertDoesNotExist()
        onNodeWithText("Nostr profile").assertIsDisplayed()
        onNodeWithText("Curated entries").assertIsDisplayed()
    }

    @Test
    fun `a NIP-17 room has no nostr identity and so no rows`() = render(
        state(groupNostrProfile = null, mlsGroupState = null, canEditNostrProfile = false)
    ) {
        onNodeWithText("Nostr profile").assertDoesNotExist()
        onNodeWithText("Not described yet").assertDoesNotExist()

        // The other four go with it: all five describe a nostr identity this
        // room does not have, and so does the paste.
        onNodeWithText("Relays").assertDoesNotExist()
        onNodeWithText("Posts").assertDoesNotExist()
        onNodeWithText("Curated schemas").assertDoesNotExist()
        onNodeWithText("Curated entries").assertDoesNotExist()
        onNodeWithText("Propose event").assertDoesNotExist()

        // The rest of the screen is untouched, so the rows' absence is an
        // absence rather than a screen that failed to draw.
        onNodeWithText("Subgroups").assertIsDisplayed()
    }

    private fun state(
        groupNostrProfile: GroupNostrProfile?,
        relayLists: List<GroupRelayList> = GroupRelayList.allAmong(emptyList(), chatRoomId),
        posts: List<GroupPost> = emptyList(),
        schemas: List<GroupCuratedSchema> = emptyList(),
        entries: List<GroupCuratedEntry> = emptyList(),
        canEditNostrProfile: Boolean = true,
        mlsGroupState: String? = "state"
    ) = ChatRoomDetailUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(mlsGroupState),
        groupKeyState = GroupSignedWorkFixtures.groupKeyState,
        groupNostrProfile = groupNostrProfile,
        groupRelayLists = relayLists,
        groupPosts = posts,
        groupCuratedSchemas = schemas,
        groupCuratedEntries = entries,
        canEditNostrProfile = canEditNostrProfile
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
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
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
