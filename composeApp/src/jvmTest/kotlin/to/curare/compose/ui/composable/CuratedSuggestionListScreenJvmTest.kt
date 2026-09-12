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
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.CuratedSuggestion
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldConfig
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.CuratedSuggestionListUIState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the queue screen says, in each of the states it can be in.
 *
 * Three things are worth pinning. **The row is driven by a schema the screen
 * has never seen**, so what it puts on a row is a decision -- the fields that
 * say what an entry is, labelled, and not the links -- and that decision is
 * checked against a schema with one of each. **"Curated" is per row**, and a
 * screen that marked the wrong row would send a member to re-curate what the
 * group had already taken up. And **empty is not the same as still looking**:
 * the screen has both and must say which, since a queue called empty a second
 * before its rows arrive teaches a member not to believe it.
 */
@OptIn(ExperimentalTestApi::class)
class CuratedSuggestionListScreenJvmTest {

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val schema = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = "c".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
        schema = CuratedSchema(
            identifier = "films",
            title = "Film suggestion",
            name = "Films worth translating",
            description = "Films the group would subtitle.",
            visibility = CuratedVisibility.Public,
            domain = "films.example",
            fields = listOf(
                CuratedField.IDENTIFIER,
                CuratedField.TITLE,
                CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year"),
                CuratedField("type", CuratedFieldType.Enum, isRequired = true, label = "Type"),
                CuratedField(
                    "watchUrl",
                    CuratedFieldType.Url,
                    isRequired = false,
                    label = "Watch URL",
                    config = CuratedFieldConfig(tag = "r", marker = "watch")
                ),
                CuratedField(
                    "review",
                    CuratedFieldType.LongText,
                    isRequired = false,
                    label = "Review",
                    config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG)
                ),
            ),
            relays = listOf("wss://relay.one.example")
        )
    )

    @Test
    fun `the queue is named after the list, counted, and read from where the schema says`() = render(
        state(
            suggestions = listOf(
                suggestion("The Rise and Rise of Bitcoin", 1_700_000_900, isCurated = true),
                suggestion("Magic Money", 1_700_000_000, isCurated = false)
            )
        )
    ) {
        // The domain, as the card on the group's screen names it.
        onNodeWithText("films.example").assertIsDisplayed()
        onNodeWithText("2 suggestions · 1 curated").assertIsDisplayed()
        onNodeWithText("Read from relay.one.example").assertIsDisplayed()

        // Newest first.
        val newest = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot().top
        val oldest = onNodeWithText("Magic Money").getBoundsInRoot().top
        assertTrue(newest < oldest, "the queue was drawn oldest first")
    }

    @Test
    fun `a row shows what an entry is, not where to find it, and who suggested it`() = render(
        state(
            suggestions = listOf(
                suggestion(
                    "Magic Money",
                    1_700_000_000,
                    isCurated = false,
                    suggester = Profile(
                        publicKey = ada,
                        displayName = "Ada",
                        nostrEventId = "e".repeat(64),
                        createdAt = Instant.fromEpochSeconds(1_700_000_000)
                    )
                )
            )
        )
    ) {
        // Labelled, because the screen does not know this list's fields any
        // better than a stranger does; the link left to the sheet.
        onNodeWithText("Year: 2018 · Type: documentary").assertIsDisplayed()
        onNodeWithText("https://example.com/watch").assertDoesNotExist()
        // The body, where the list has one.
        onNodeWithText("Follows the early community.").assertIsDisplayed()
        onNodeWithText("Suggested by Ada on", substring = true).assertIsDisplayed()

        onNodeWithText("1 suggestion").assertIsDisplayed()
        onNodeWithText("Curated").assertDoesNotExist()
    }

    @Test
    fun `only the row the group has taken up is marked`() = render(
        state(
            suggestions = listOf(
                suggestion("The Rise and Rise of Bitcoin", 1_700_000_900, isCurated = true),
                suggestion("Magic Money", 1_700_000_000, isCurated = false)
            )
        )
    ) {
        onAllNodesWithText("Curated").assertCountEquals(1)

        val mark = onNodeWithText("Curated").getBoundsInRoot()
        val curated = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot()
        val waiting = onNodeWithText("Magic Money").getBoundsInRoot()

        assertTrue(mark.top < waiting.top, "the mark fell onto the row still waiting")
        assertTrue(mark.bottom > curated.top, "the mark floated above the row it is about")
    }

    @Test
    fun `a queue still being asked for is looking, not empty`() = render(
        state(suggestions = emptyList(), isAsking = true)
    ) {
        onNodeWithText("Asking the list's relays…").assertIsDisplayed()
        onNodeWithText("Nobody has suggested anything to this list yet.").assertDoesNotExist()
        onNodeWithText("Ask again").assertDoesNotExist()
    }

    @Test
    fun `an empty queue says so, and offers to ask again`() = render(
        state(suggestions = emptyList(), isAsking = false)
    ) {
        onNodeWithText("Nobody has suggested anything to this list yet.").assertIsDisplayed()
        onNodeWithText("Ask again").assertIsDisplayed()
        onNodeWithText("Asking the list's relays…").assertDoesNotExist()
        onNodeWithText("0 suggestions").assertIsDisplayed()
    }

    @Test
    fun `the sheet has every field the entry answered, the link included, and the way in`() =
        runDesktopComposeUiTest(width = 400, height = 2000) {
            var accepted = false

            setContent {
                CurareTheme {
                    ProvideSnackbarHost {
                        CuratedSuggestionSheetContent(
                            suggestion = suggestion("Magic Money", 1_700_000_000, isCurated = false),
                            schema = schema.schema,
                            onAccept = { accepted = true }
                        )
                    }
                }
            }

            onNodeWithText("Magic Money").assertIsDisplayed()

            // As the schema labels them, in the schema's order.
            onNodeWithText("Year").assertIsDisplayed()
            onNodeWithText("2018").assertIsDisplayed()
            onNodeWithText("Watch URL").assertIsDisplayed()
            onNodeWithText("https://example.com/watch").assertIsDisplayed()
            onNodeWithText("Review").assertIsDisplayed()
            onNodeWithText("Follows the early community.").assertIsDisplayed()

            // The one thing to do with it, and not the event: the form shows
            // the entry as fields, which is how the group will sign it.
            onAllNodesWithText("\"kind\":31888", substring = true).assertCountEquals(0)
            onAllNodesWithText("Copy the suggestion").assertCountEquals(0)
            onNodeWithText("Accept this suggestion").assertIsDisplayed().performClick()
            assertTrue(accepted, "the button did not open the form")
        }

    @Test
    fun `a suggestion the group already took up offers a revision, not a second acceptance`() =
        runDesktopComposeUiTest(width = 400, height = 2000) {
            setContent {
                CurareTheme {
                    ProvideSnackbarHost {
                        CuratedSuggestionSheetContent(
                            suggestion = suggestion("Magic Money", 1_700_000_000, isCurated = true),
                            schema = schema.schema,
                            onAccept = {}
                        )
                    }
                }
            }

            onNodeWithText("Taken up by the group into the list.").assertIsDisplayed()
            onNodeWithText("Revise the list's entry").assertIsDisplayed()
            onAllNodesWithText("Accept this suggestion").assertCountEquals(0)
        }

    private fun suggestion(
        title: String,
        createdAt: Long,
        isCurated: Boolean,
        suggester: Profile? = null
    ): CuratedSuggestion {
        val id = createdAt.toString().padStart(64, 'e')
        val identifier = title.lowercase().replace(' ', '-')

        return CuratedSuggestion(
            entry = CuratedEntry(
                kind = CuratedSchemaEvent.SUGGESTION_KIND,
                id = id,
                pubKey = suggester?.publicKey ?: bob,
                createdAt = createdAt,
                identifier = identifier,
                title = title,
                values = mapOf(
                    "identifier" to listOf(identifier),
                    "title" to listOf(title),
                    "year" to listOf("2018"),
                    "type" to listOf("documentary"),
                    "watchUrl" to listOf("https://example.com/watch"),
                    "review" to listOf("Follows the early community."),
                )
            ),
            event = NostrEvent(
                id = id,
                pubKey = suggester?.publicKey ?: bob,
                kind = CuratedSchemaEvent.SUGGESTION_KIND,
                tags = arrayOf(arrayOf("d", identifier), arrayOf("title", title)),
                content = "Follows the early community.",
                sig = "b".repeat(128),
                createdAt = Instant.fromEpochSeconds(createdAt)
            ),
            suggester = suggester,
            isCurated = isCurated
        )
    }

    private fun state(
        suggestions: List<CuratedSuggestion>,
        isAsking: Boolean = false
    ) = CuratedSuggestionListUIState.Loaded(
        localChatRoom = LocalChatRoom(
            chatRoom = ChatRoom(
                id = chatRoomId,
                userPublicKey = ada,
                subject = "Translation room",
                description = null,
                initialGiftWrapPayloadId = "sdfaer",
                mlsGroupState = "state"
            )
        ),
        schema = schema,
        relays = listOf("wss://relay.one.example"),
        suggestions = suggestions,
        isAsking = isAsking
    )

    /**
     * The screen at a phone's width and a window tall enough to compose the
     * whole of it. The state is passed in, so the NO_OP repositories are never
     * asked for anything: `initiate` only runs for a screen that arrives loading.
     */
    private fun render(
        uiState: CuratedSuggestionListUIState,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CuratedSuggestionListScreen(
                            activeUserPublicKey = ada,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            identifier = "films",
                            initialCuratedSuggestionListUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            onNavigateBack = {},
                            onNavigateToRoute = {}
                        )
                    }
                }
            }
        }

        assertions()
    }
}
