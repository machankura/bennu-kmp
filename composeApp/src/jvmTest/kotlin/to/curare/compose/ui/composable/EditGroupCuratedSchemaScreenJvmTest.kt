package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldConfig
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedSchemaTemplate
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.EditGroupCuratedSchemaViewModel
import to.curare.compose.ui.view.state.EditGroupCuratedSchemaUIState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the schema editor puts in front of a member, in the two states it opens in.
 *
 * **A new list starts filled in.** The two fields every list has and the group's
 * own relays are on the form before anything is typed, because they are what the
 * group will sign whether or not the member adds to them -- and a form that
 * showed an empty list and then signed two fields would be lying about what it
 * proposed. A new list from a template starts at the template: its fields as
 * rows and its rules as chips, since the picker before this screen promised
 * that shape and the form is where the promise is kept.
 *
 * **An existing list opens as an edit.** Its identifier is locked and the form
 * says why, its rules show which fields they name, and the suggesters a closed
 * list admits are there to be read and removed. The rows are what a member
 * checks before proposing, and each is an intention a later change to the
 * `LazyColumn` could drop without anything else noticing.
 *
 * **The sheet offers a type its own settings.** A url field gets "https only"
 * and a year field does not, because a setting the event gives no meaning to
 * would be written into the schema and mean nothing.
 */
@OptIn(ExperimentalTestApi::class)
class EditGroupCuratedSchemaScreenJvmTest {

    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val suggester = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"

    private val films = CuratedSchema(
        identifier = "films",
        title = "Film suggestion",
        name = "Films worth translating",
        description = "Films the group would subtitle, with a link to watch each one.",
        visibility = CuratedVisibility.Closed,
        fields = listOf(
            CuratedField.IDENTIFIER,
            CuratedField.TITLE,
            CuratedField(
                name = "watchUrl",
                type = CuratedFieldType.Url,
                isRequired = false,
                label = "Where to watch",
                config = CuratedFieldConfig(tag = "r", marker = "watch", max = 500),
            ),
            CuratedField(
                name = "imdbUrl",
                type = CuratedFieldType.Url,
                isRequired = false,
                label = "IMDb page",
                config = CuratedFieldConfig(tag = "r", marker = "imdb", max = 500),
            ),
        ),
        requireAny = listOf(listOf("watchUrl", "imdbUrl")),
        suggesters = listOf(suggester),
        relays = listOf("wss://relay.one.example"),
    )

    @Test
    fun `a new list starts with the two fields every list has and the group's relays`() = render(
        state(existing = null, defaultRelays = listOf("wss://relay.one.example/"))
    ) {
        // By their supporting line: "Identifier" is also the label over the
        // identifier field above, and the row is the one that says what it is.
        onNodeWithText("identifier · Token · Required · Derived").assertIsDisplayed()
        onNodeWithText("title · Text · Required").assertIsDisplayed()
        onNodeWithText("wss://relay.one.example/").assertIsDisplayed()

        onNodeWithText("Public").assertIsSelected()
        // A public list admits anyone, so there is no list of who it admits.
        onNodeWithText("Suggesters").assertDoesNotExist()

        onNodeWithText("Add field").assertIsDisplayed()
        onNodeWithText("Add rule").assertIsDisplayed()
        onNodeWithText("Propose schema").assertIsDisplayed()

        // Nothing locks a new identifier: the member is choosing it.
        onNodeWithText("The identifier names this list in every reply", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a new list from a template starts at the template's fields and rules`() = render(
        state(existing = null, defaultRelays = listOf("wss://relay.one.example/")),
        template = CuratedSchemaTemplate.Links.key
    ) {
        // The template's fields, in its order, the mandatory two included --
        // and the identifier still marked derived, since the template records
        // its rule in the hint rather than prompting for the value.
        onNodeWithText("identifier · Token · Required · Derived").assertIsDisplayed()
        val title = onNodeWithText("title · Text · Required").getBoundsInRoot().top
        val link = onNodeWithText("link · Url · Optional").getBoundsInRoot().top
        val body = onNodeWithText("body · Long text · Optional").getBoundsInRoot().top
        val image = onNodeWithText("image · Image · Optional").getBoundsInRoot().top
        val flair = onNodeWithText("flair · One of a list · Optional").getBoundsInRoot().top
        assertTrue(title < link && link < body && body < image && image < flair, "the fields were drawn out of order")

        // The rule the template carries, as a row of chips with its two fields
        // selected. The chip is the one node saying exactly "link": the row
        // above is headed by the label, "Link", and its supporting line says more.
        onNodeWithText("At least one of").assertIsDisplayed()
        onAllNodesWithText("link").assertCountEquals(1)
        onNodeWithText("link").assertIsSelected()
        onNodeWithText("body").assertIsSelected()
        onNodeWithText("flair").assertIsNotSelected()

        // Everything else is as a new list is.
        onNodeWithText("wss://relay.one.example/").assertIsDisplayed()
        onNodeWithText("Public").assertIsSelected()
        onNodeWithText("Propose schema").assertIsDisplayed()
    }

    @Test
    fun `an existing list opens as an edit with its identifier locked`() = render(
        state(existing = existing(films))
    ) {
        onNodeWithText("films").assertIsDisplayed()
        onNodeWithText("The identifier names this list in every reply", substring = true)
            .assertIsDisplayed()

        onNodeWithText("Closed").assertIsSelected()

        // Every field, in the order it will be written.
        val identifier = onNodeWithText("identifier · Token · Required · Derived").getBoundsInRoot().top
        val watch = onNodeWithText("Where to watch").getBoundsInRoot().top
        val imdb = onNodeWithText("IMDb page").getBoundsInRoot().top
        assertTrue(identifier < watch && watch < imdb, "the fields were drawn out of order")

        // The rule names its fields, and the suggesters are readable as npubs.
        onNodeWithText("At least one of").assertIsDisplayed()
        onNodeWithText("Suggesters").assertIsDisplayed()
        onNodeWithText(suggester.hexToNpubHrp()).assertIsDisplayed()
        onNodeWithText("wss://relay.one.example").assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key is told so and the button is inert`() = render(
        state(existing = existing(films), canSign = false)
    ) {
        onNodeWithText("This group has no shared key, so it cannot sign a curated schema.", substring = true)
            .assertIsDisplayed()
        onNodeWithContentDescription("Propose schema").assertIsNotEnabled()
    }

    @Test
    fun `the sheet offers a type its own settings and pins a mandatory field`() {
        // A url field: https is a thing it can be told, options are not.
        renderSheet(field = films.fields[2], index = 2, isMandatory = false) {
            onNodeWithText("Edit field").assertIsDisplayed()
            onNodeWithText("Url").assertIsSelected()
            onNodeWithText("https only").assertIsDisplayed()
            onNodeWithText("Options").assertDoesNotExist()
            onNodeWithText("Minimum value").assertDoesNotExist()
            onNodeWithText("Remove field").assertIsDisplayed()

            // Changing the type changes what is offered.
            onNodeWithText("One of a list").performClick()
            onNodeWithText("Options").assertIsDisplayed()
            onNodeWithText("https only").assertDoesNotExist()
        }

        // The identifier: required whatever the switch says, and not removable.
        renderSheet(field = films.fields[0], index = 0, isMandatory = true) {
            onNodeWithContentDescription("An entry must have this field").assertIsOn()
            onNodeWithContentDescription("An entry must have this field").assertIsNotEnabled()
            onNodeWithText("Remove field").assertDoesNotExist()
        }
    }

    private fun existing(schema: CuratedSchema) = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = "c".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = CuratedSchemaEvent.template(schema, createdAt = 1_700_000_000).tags,
            content = schema.description,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        schema = schema.normalized(),
    )

    private fun state(
        existing: GroupCuratedSchema?,
        defaultRelays: List<String> = emptyList(),
        canSign: Boolean = true,
    ) = EditGroupCuratedSchemaUIState.Loaded(
        localChatRoom = LocalChatRoom(
            chatRoom = ChatRoom(
                id = chatRoomId,
                userPublicKey = "a".repeat(64),
                subject = "Translation room",
                description = "A group translating hard books.",
                initialGiftWrapPayloadId = "sdfaer",
                mlsGroupState = "state"
            )
        ),
        existing = existing,
        defaultRelays = defaultRelays,
        canSign = canSign,
    )

    /**
     * The screen at a phone's width and a window tall enough to compose the whole
     * of it, since a `LazyColumn` does not lay out what it would not show.
     */
    private fun render(
        uiState: EditGroupCuratedSchemaUIState,
        template: String? = null,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 3000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        EditGroupCuratedSchemaScreen(
                            activeUserPublicKey = "a".repeat(64),
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            identifier = (uiState as? EditGroupCuratedSchemaUIState.Loaded)
                                ?.existing?.identifier,
                            template = template,
                            initialEditGroupCuratedSchemaUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            frostSigningRepository =
                                FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                            onNavigateToRouteAndPopUpInclusive = {},
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        assertions()
    }

    /**
     * The sheet's content on its own: a bottom sheet is a popup with its own
     * window, which a layout test cannot reach into.
     */
    private fun renderSheet(
        field: CuratedField,
        index: Int,
        isMandatory: Boolean,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        FieldEditorSheetContent(
                            edit = EditGroupCuratedSchemaViewModel.FieldEdit(index = index, field = field),
                            isMandatory = isMandatory,
                            onCommit = { null },
                            onRemove = {}
                        )
                    }
                }
            }
        }

        assertions()
    }
}
