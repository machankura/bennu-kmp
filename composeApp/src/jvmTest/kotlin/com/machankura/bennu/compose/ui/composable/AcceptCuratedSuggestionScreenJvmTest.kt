package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.bennu.compose.nostr.CuratedSuggestion
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedField
import com.machankura.bennu.compose.nostr.curated.CuratedFieldConfig
import com.machankura.bennu.compose.nostr.curated.CuratedFieldType
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.NostrRepository
import com.machankura.bennu.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.state.AcceptCuratedSuggestionUIState
import kotlin.test.Test
import kotlin.time.Instant

/**
 * What the accept form puts on the screen, for a schema it has never seen.
 *
 * Three things worth pinning. **The inputs are the schema's**: one per field
 * the form asks for, labelled as the schema labels them and seeded with what
 * was suggested, with an enum as its options rather than a box to misspell
 * one in. **What is kept is shown, not hidden**: the identifier and the
 * derived fields are on the screen under their own heading, so what the
 * member sees is what the group will sign. And **a refusal is drawn under the
 * input it is about**, since the button doing nothing is indistinguishable
 * from a missed tap.
 */
@OptIn(ExperimentalTestApi::class)
class AcceptCuratedSuggestionScreenJvmTest {

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
                CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year", config = CuratedFieldConfig(min = 1900, max = 2100)),
                CuratedField("type", CuratedFieldType.Enum, isRequired = true, label = "Type", config = CuratedFieldConfig(options = listOf("movie", "documentary"))),
                CuratedField("watchUrl", CuratedFieldType.Url, isRequired = false, label = "Watch URL", config = CuratedFieldConfig(tag = "r", marker = "watch", repeat = true)),
                CuratedField("hashtags", CuratedFieldType.Token, isRequired = false, label = "Hashtags", config = CuratedFieldConfig(tag = "t", repeat = true, derived = true)),
                CuratedField("review", CuratedFieldType.LongText, isRequired = false, label = "Review", config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG, hint = "Why the group should watch it")),
            ),
            relays = listOf("wss://relay.one.example")
        ).normalized()
    )

    private val suggestion: CuratedSuggestion = CuratedSuggestion.queueOf(
        schema,
        listOf(
            LocalNostrEvent(
                nostrEvent = NostrEvent(
                    id = "1".repeat(64),
                    pubKey = bob,
                    kind = CuratedSchemaEvent.SUGGESTION_KIND,
                    tags = arrayOf(
                        arrayOf("d", "magic-money"),
                        arrayOf("title", "Magic Money"),
                        arrayOf("year", "2018"),
                        arrayOf("type", "documentary"),
                        arrayOf("r", "https://example.com/watch", "watch"),
                        arrayOf("t", "bitcoin"),
                        arrayOf("a", schema.coordinate(), "", "root"),
                    ),
                    content = "Follows the early community.",
                    sig = "b".repeat(128),
                    createdAt = Instant.fromEpochSeconds(1_700_000_000),
                ),
                profile = null
            )
        )
    ).single()

    private fun state(canSign: Boolean = true, isCurated: Boolean = false) = AcceptCuratedSuggestionUIState.Loaded(
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
        suggestion = suggestion.copy(isCurated = isCurated),
        canSign = canSign
    )

    @Test
    fun `the inputs are the schema's, seeded from the suggestion, and what is kept is shown`() = render(state()) {
        // Twice: the heading, and the title input seeded with it.
        onAllNodesWithText("Magic Money").assertCountEquals(2)
        onNodeWithText("Suggested by ${bob.take(8)} on ", substring = true).assertIsDisplayed()
        onNodeWithText("Take this suggestion into films.example", substring = true).assertIsDisplayed()

        // The schema's labels over its values: a text field, a number, and the
        // enum as chips with the suggested one among them.
        onNodeWithText("Year").assertIsDisplayed()
        onNodeWithText("2018").assertIsDisplayed()
        onNodeWithText("Type").assertIsDisplayed()
        onNodeWithText("movie").assertIsDisplayed()
        onNodeWithText("documentary").assertIsDisplayed()
        onNodeWithText("https://example.com/watch").assertIsDisplayed()
        onNodeWithText("Follows the early community.").assertIsDisplayed()
        // The schema's hint under the input it is for.
        onNodeWithText("Why the group should watch it").assertIsDisplayed()
        // A field that repeats can be given another value.
        onNodeWithText("Add another").assertIsDisplayed()

        // Kept, and said to be: the identifier and the derived hashtags.
        onNodeWithText("Kept from the suggestion").assertIsDisplayed()
        onNodeWithText("Identifier").assertIsDisplayed()
        onNodeWithText("magic-money").assertIsDisplayed()
        onNodeWithText("Hashtags").assertIsDisplayed()
        onNodeWithText("bitcoin").assertIsDisplayed()

        onNodeWithText("Propose entry").assertIsDisplayed()
        onAllNodesWithText("The group already has an entry for this", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a refusal is drawn under the input it is about`() = render(state()) {
        // The suggested year is replaced with one the schema's range refuses,
        // by way of the field: the test types where a member would.
        onNodeWithText("2018").performTextReplacement("1850")
        onNodeWithText("Propose entry").performClick()

        onNodeWithText("At least 1900.").assertIsDisplayed()
    }

    @Test
    fun `a device without a share sees why the button is inert`() = render(state(canSign = false)) {
        onNodeWithText("This group has no shared key, so it cannot sign an entry", substring = true).assertIsDisplayed()
        onNodeWithText("Propose entry").assertIsNotEnabled()
    }

    @Test
    fun `a suggestion the group already took up says so before the button`() = render(state(isCurated = true)) {
        onNodeWithText("The group already has an entry for this", substring = true).assertIsDisplayed()
    }

    /**
     * The screen at a phone's width and a window tall enough to compose the
     * whole of it. The state is passed in, so the NO_OP repositories are never
     * asked for anything: `initiate` only runs for a screen that arrives loading.
     */
    private fun render(
        uiState: AcceptCuratedSuggestionUIState,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2400) {
        setContent {
            BennuTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AcceptCuratedSuggestionScreen(
                            activeUserPublicKey = ada,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            identifier = "films",
                            suggestionId = suggestion.id,
                            initialAcceptCuratedSuggestionUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                            onNavigateBack = {},
                            onNavigateToRouteAndPopUpInclusive = {}
                        )
                    }
                }
            }
        }

        assertions()
    }
}
