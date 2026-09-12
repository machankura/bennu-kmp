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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.schema
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.CuratedSuggestionListRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupCuratedSchemaRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.GroupCuratedSchemasUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schemas of the lists the group curates on their own screen: one card per
 * list, most recently signed first, each with its queue and its broadcast, and
 * two ways into the editor -- the card for a revision, the button for a new
 * list -- for a member holding a share.
 */
@OptIn(ExperimentalTestApi::class)
class GroupCuratedSchemasScreenJvmTest {

    private val films = schema("films", "Films worth translating", 1_700_000_900)
    private val books = schema("books", "Books worth translating", 1_700_000_000)

    @Test
    fun `the schemas are drawn most recently signed first, one card per list`() = render(
        state(schemas = listOf(films, books))
    ) {
        val newest = onNodeWithText("Films worth translating").getBoundsInRoot().top
        val oldest = onNodeWithText("Books worth translating").getBoundsInRoot().top
        assertTrue(newest < oldest, "the schemas were drawn oldest first")

        // What an entry asks for, and who may suggest one.
        onAllNodesWithText("Fields: Identifier, Title", substring = true).assertCountEquals(2)
        onAllNodesWithText("Public · ", substring = true).assertCountEquals(2)
        onNodeWithText("Add schema").assertIsDisplayed()
    }

    @Test
    fun `every schema offers its own queue, under its own card`() {
        var opened: Route? = null

        render(state(schemas = listOf(films, books)), onNavigateToRoute = { opened = it }) {
            // One per list, because each list has its own queue -- and under the
            // card it belongs to rather than once for the screen, since a button
            // between two cards would say nothing about which it opened.
            val buttons = onAllNodesWithText("View suggestions")
            buttons.assertCountEquals(2)

            val filmsCard = onNodeWithText("Films worth translating").getBoundsInRoot().top
            val booksCard = onNodeWithText("Books worth translating").getBoundsInRoot().top
            val first = buttons[0].getBoundsInRoot().top
            val second = buttons[1].getBoundsInRoot().top

            assertTrue(filmsCard < first && first < booksCard, "the first queue button is not under its card")
            assertTrue(booksCard < second, "the second queue button is not under its card")

            buttons[1].performClick()
        }

        assertEquals(
            CuratedSuggestionListRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                identifier = "books"
            ),
            opened
        )
    }

    @Test
    fun `every schema offers a broadcast, beside its queue`() {
        var opened: Route? = null

        render(state(schemas = listOf(films, books)), onNavigateToRoute = { opened = it }) {
            val buttons = onAllNodesWithText("Broadcast")
            buttons.assertCountEquals(2)

            val filmsCard = onNodeWithText("Films worth translating").getBoundsInRoot().top
            val booksCard = onNodeWithText("Books worth translating").getBoundsInRoot().top
            assertTrue(filmsCard < buttons[0].getBoundsInRoot().top, "the first broadcast is not under its card")
            assertTrue(booksCard < buttons[1].getBoundsInRoot().top, "the second broadcast is not under its card")

            buttons[0].performClick()
        }

        assertEquals(
            BroadcastGroupSignedEventRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                signedEventId = films.signedEvent.id
            ),
            opened
        )
    }

    @Test
    fun `a card opens the editor on its own schema, for a member holding a share of the key`() {
        var opened: Route? = null

        render(state(schemas = listOf(films, books)), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Books worth translating").performClick()
        }

        assertEquals(
            EditGroupCuratedSchemaRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                identifier = "books"
            ),
            opened
        )
    }

    @Test
    fun `the button opens the editor on no schema at all`() {
        var opened: Route? = null

        render(state(schemas = listOf(films)), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Add schema").performClick()
        }

        assertEquals(
            EditGroupCuratedSchemaRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                identifier = null
            ),
            opened
        )
    }

    @Test
    fun `a member holding no share of the key reads the schemas and is offered no editor`() {
        var opened: Route? = null

        render(state(schemas = listOf(films), canSign = false), onNavigateToRoute = { opened = it }) {
            // A schema exists to be read by people who were never in the room, so
            // a member without a share reads it like anybody else and simply
            // cannot propose a revision -- neither a new one nor an edit.
            onNodeWithText("Films worth translating").assertIsDisplayed()
            onNodeWithText("Add schema").assertDoesNotExist()
            onNodeWithContentDescription("Edit schema").assertDoesNotExist()

            // The queue is the one thing on the screen the group did not write,
            // and reading it takes no share of the key.
            onNodeWithText("View suggestions").assertIsDisplayed()

            // A card that cannot be opened goes nowhere when tapped.
            onNodeWithText("Films worth translating").performClick()
        }

        assertNull(opened, "a card with no editor behind it opened something")
    }

    @Test
    fun `a group that curates nothing says so, and offers to add a schema`() {
        var opened: Route? = null

        render(state(schemas = emptyList()), onNavigateToRoute = { opened = it }) {
            onNodeWithText("This group hasn't published a curated schema yet.", substring = true)
                .assertIsDisplayed()
            onAllNodesWithText("View suggestions").assertCountEquals(0)
            onNodeWithText("Add schema").assertIsDisplayed().performClick()
        }

        assertEquals(
            EditGroupCuratedSchemaRoute(activeUserPublicKey, chatRoomId, relayHint = null, identifier = null),
            opened
        )
    }

    private fun state(
        schemas: List<GroupCuratedSchema>,
        canSign: Boolean = true
    ) = GroupCuratedSchemasUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupCuratedSchemas = schemas,
        canSign = canSign
    )

    private fun render(
        uiState: GroupCuratedSchemasUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupCuratedSchemasScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialGroupCuratedSchemasUIState = uiState,
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
