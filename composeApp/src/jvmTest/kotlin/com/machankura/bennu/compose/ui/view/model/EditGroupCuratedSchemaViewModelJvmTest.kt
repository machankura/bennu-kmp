package com.machankura.bennu.compose.ui.view.model

import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.extensions.hexToNpubHrp
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedField
import com.machankura.bennu.compose.nostr.curated.CuratedFieldConfig
import com.machankura.bennu.compose.nostr.curated.CuratedFieldType
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaProblem
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaTemplate
import com.machankura.bennu.compose.nostr.curated.CuratedUnsupportedField
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.ui.view.state.EditGroupCuratedSchemaUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The decisions the schema editor makes that nothing else can check.
 *
 * **What a new list starts as.** Its template's fields and rules -- the two
 * fields every list has, when no shape was chosen -- and the group's own relays,
 * so that what the member sees is what the group will sign rather than what
 * `CuratedSchema.normalized` will quietly add.
 *
 * **What cannot be taken away.** A field writing to `d` or `title` cannot be
 * removed, because the event would only grow it back -- so the editor has to
 * refuse rather than let a member believe they removed it.
 *
 * **What counts as a change.** The button proposes only when the schema differs
 * from the one the group signed, and getting that wrong is quiet in both
 * directions: too eager and every visit re-dates a decision the group did not
 * make; too shy and an edit is dropped on a screen that said it had been sent.
 */
class EditGroupCuratedSchemaViewModelJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val films = CuratedSchema(
        identifier = "films",
        title = "Film suggestion",
        name = "Films worth translating",
        description = "Films the group would subtitle.",
        visibility = CuratedVisibility.Closed,
        fields = listOf(
            CuratedField.IDENTIFIER,
            CuratedField.TITLE,
            CuratedField("watchUrl", CuratedFieldType.Url, isRequired = false, config = CuratedFieldConfig(tag = "r", marker = "watch")),
            CuratedField("imdbUrl", CuratedFieldType.Url, isRequired = false, config = CuratedFieldConfig(tag = "r", marker = "imdb")),
        ),
        requireAny = listOf(listOf("watchUrl", "imdbUrl")),
        suggesters = listOf("e".repeat(64)),
        relays = listOf("wss://relay.one.example"),
    )

    private fun viewModel(identifier: String? = null, template: String? = null) = EditGroupCuratedSchemaViewModel(
        chatRoomId = chatRoomId,
        activeUserPublicKey = "a".repeat(64),
        relayHint = null,
        identifier = identifier,
        template = template,
        initialEditGroupCuratedSchemaUIState = EditGroupCuratedSchemaUIState.Loading,
        chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
        frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
    )

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = "a".repeat(64),
            subject = "Translation room",
            description = null,
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = "state"
        )
    )

    /** The schema as the group signed it. Not verified here: the editor does not. */
    private fun existing(schema: CuratedSchema = films) = GroupCuratedSchema(
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

    private fun newSchema(defaultRelays: List<String> = listOf("wss://relay.one.example")) =
        EditGroupCuratedSchemaUIState.Loaded(
            localChatRoom = localChatRoom,
            existing = null,
            defaultRelays = defaultRelays,
            canSign = true,
        )

    private fun editing(schema: CuratedSchema = films) = EditGroupCuratedSchemaUIState.Loaded(
        localChatRoom = localChatRoom,
        existing = existing(schema),
        defaultRelays = listOf("wss://relay.two.example"),
        canSign = true,
    )

    @Test
    fun `a new schema starts with the two fields every list has and the group's relays`() {
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(newSchema())

        assertEquals(listOf("d", "title"), viewModel.fields.map { it.tag() })
        assertTrue(viewModel.fields.all { it.isRequired })
        assertEquals(CuratedVisibility.Public, viewModel.visibility)
        assertEquals(listOf("wss://relay.one.example"), viewModel.relays)
        assertEquals(emptyList(), viewModel.requireAny)
        assertEquals(emptyList(), viewModel.suggesters)
    }

    @Test
    fun `a new schema from a template starts at the template's fields and rules`() {
        val viewModel = viewModel(template = CuratedSchemaTemplate.Links.key)
        viewModel.seedWorkingCopy(newSchema())

        assertEquals(CuratedSchemaTemplate.Links.fields, viewModel.fields.toList())
        assertEquals(CuratedSchemaTemplate.Links.requireAny, viewModel.requireAny.toList())
        // The identifier is the template's: derived, with its rule in the hint.
        assertTrue(viewModel.isMandatory(viewModel.fields[0]))
        assertTrue(viewModel.fields[0].config.hint.orEmpty().endsWith("[rule:link-or-title]"))
        // What the template does not decide is as any new list's.
        assertEquals(CuratedVisibility.Public, viewModel.visibility)
        assertEquals(listOf("wss://relay.one.example"), viewModel.relays)
        assertEquals(emptyList(), viewModel.suggesters)

        // And the draft it makes is a usable schema as it stands, so a member
        // who fills in the identity and presses the button proposes the shape.
        val draft = viewModel.draft("links", "Link suggestion", "Links", "Things worth a look.", "", "")
        assertTrue(draft.isUsable())
        assertEquals(listOf(listOf("link", "body")), draft.requireAny)
    }

    @Test
    fun `a template key this build does not know starts at the two fields every list has`() {
        val viewModel = viewModel(template = "gallery")
        viewModel.seedWorkingCopy(newSchema())

        assertEquals(CuratedSchemaTemplate.Custom.fields, viewModel.fields.toList())
        assertEquals(listOf("d", "title"), viewModel.fields.map { it.tag() })
        assertEquals(emptyList(), viewModel.requireAny)
    }

    @Test
    fun `an existing schema starts as itself, and its relays are its own`() {
        // A template beside an identifier is ignored: an edit starts from the
        // schema the group signed, whatever shape the route also names.
        val viewModel = viewModel("films", template = CuratedSchemaTemplate.Catalogue.key)
        viewModel.seedWorkingCopy(editing())

        assertEquals(films.fields, viewModel.fields.toList())
        assertEquals(films.requireAny, viewModel.requireAny.toList())
        assertEquals(films.suggesters, viewModel.suggesters.toList())
        assertEquals(CuratedVisibility.Closed, viewModel.visibility)
        // Not the defaults the state also carries: those are for a new list.
        assertEquals(listOf("wss://relay.one.example"), viewModel.relays)
    }

    @Test
    fun `seeding again leaves an edit alone`() {
        // The effect that seeds is keyed on a state the view model can re-emit, so
        // running twice has to be harmless -- otherwise a reload halfway through an
        // edit throws the edit away.
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())
        assertTrue(viewModel.addRelay("wss://relay.two.example"))

        viewModel.seedWorkingCopy(editing())

        // The added relay is normalized, which is how the group's other relay
        // lists are written; the signed one is kept as the group wrote it.
        assertEquals(listOf("wss://relay.one.example", "wss://relay.two.example/"), viewModel.relays)
    }

    @Test
    fun `a relay already signed into the schema is a duplicate with or without the slash`() {
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())

        assertFalse(viewModel.addRelay("wss://relay.one.example"))
        assertFalse(viewModel.addRelay("wss://relay.one.example/"))
        assertEquals(EditGroupCuratedSchemaViewModel.AddRelayFailure.AlreadyListed, viewModel.addRelayFailure)
        assertEquals(listOf("wss://relay.one.example"), viewModel.relays)
    }

    @Test
    fun `a mandatory field cannot be removed and another one can`() {
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())

        assertTrue(viewModel.isMandatory(viewModel.fields[0]))
        assertTrue(viewModel.isMandatory(viewModel.fields[1]))
        assertFalse(viewModel.isMandatory(viewModel.fields[2]))

        viewModel.removeField(0)
        viewModel.removeField(1)
        assertEquals(listOf("identifier", "title", "watchUrl", "imdbUrl"), viewModel.fields.map { it.name })

        // A field that goes takes its place in the rules with it: a rule naming a
        // field that no longer exists would silently never be satisfied.
        viewModel.removeField(2)
        assertEquals(listOf("identifier", "title", "imdbUrl"), viewModel.fields.map { it.name })
        assertEquals(listOf(listOf("imdbUrl")), viewModel.requireAny.toList())
    }

    @Test
    fun `renaming a field follows through to the rules that named it`() {
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())

        viewModel.startEditingField(2)
        assertNull(viewModel.commitField(viewModel.fields[2].copy(name = "watch")))

        assertEquals(listOf("identifier", "title", "watch", "imdbUrl"), viewModel.fields.map { it.name })
        assertEquals(listOf(listOf("watch", "imdbUrl")), viewModel.requireAny.toList())
        assertNull(viewModel.editingField, "the sheet stayed open after a commit")
    }

    @Test
    fun `a field name is one word and belongs to one field`() {
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())

        viewModel.startAddingField()
        val draft = viewModel.editingField!!.field

        assertEquals(EditGroupCuratedSchemaViewModel.FieldProblem.NameMissing, viewModel.commitField(draft.copy(name = "   ")))
        assertEquals(EditGroupCuratedSchemaViewModel.FieldProblem.NameHasSpaces, viewModel.commitField(draft.copy(name = "watch url")))
        assertEquals(EditGroupCuratedSchemaViewModel.FieldProblem.NameTaken, viewModel.commitField(draft.copy(name = "imdbUrl")))
        assertEquals(4, viewModel.fields.size, "a refused field went in anyway")

        assertNull(viewModel.commitField(draft.copy(name = " year ")))
        assertEquals("year", viewModel.fields.last().name)

        // Editing a field back to its own name is not taking it from anybody.
        viewModel.startEditingField(4)
        assertNull(viewModel.commitField(viewModel.fields[4].copy(label = "Year")))
    }

    @Test
    fun `a rule with fewer than two names is dropped from the draft rather than refused`() {
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(newSchema())

        viewModel.addRequireAnyGroup()
        viewModel.toggleRequireAny(0, "title")
        viewModel.addRequireAnyGroup()

        val draft = viewModel.draft("d", "t", "n", "desc", "", "")

        assertEquals(2, viewModel.requireAny.size, "the editor lost a rule being built")
        assertEquals(emptyList(), draft.requireAny)
    }

    @Test
    fun `a pubkey is an npub or hex and nothing else`() {
        val hex = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"
        // Encoded rather than typed, so the checksum is right by construction.
        val npub = hex.hexToNpubHrp()

        assertEquals(hex, EditGroupCuratedSchemaViewModel.pubkeyOrNull(hex))
        assertEquals(hex, EditGroupCuratedSchemaViewModel.pubkeyOrNull(" ${hex.uppercase()} "))
        assertEquals(hex, EditGroupCuratedSchemaViewModel.pubkeyOrNull(npub))
        assertEquals(hex, EditGroupCuratedSchemaViewModel.pubkeyOrNull("nostr:$npub"))

        listOf("", "npub1notanpub", hex.dropLast(1), "group@example.com", "nsec1" + npub.drop(5)).forEach {
            assertNull(EditGroupCuratedSchemaViewModel.pubkeyOrNull(it), "\"$it\" was taken for a pubkey")
        }

        val viewModel = viewModel()
        viewModel.seedWorkingCopy(newSchema())
        assertFalse(viewModel.addSuggester("group@example.com"))
        assertEquals(EditGroupCuratedSchemaViewModel.AddSuggesterFailure.NotAPubkey, viewModel.addSuggesterFailure)
        assertTrue(viewModel.addSuggester(npub))
        assertFalse(viewModel.addSuggester(hex))
        assertEquals(EditGroupCuratedSchemaViewModel.AddSuggesterFailure.AlreadyListed, viewModel.addSuggesterFailure)
        assertEquals(listOf(hex), viewModel.suggesters)
    }

    @Test
    fun `a url that is not a relay is refused and named`() {
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(newSchema(defaultRelays = emptyList()))

        assertFalse(viewModel.addRelay("http://relay.one.example"))
        assertEquals(EditGroupCuratedSchemaViewModel.AddRelayFailure.NotARelay, viewModel.addRelayFailure)
        // The NIP allows ws://; what the group signs is held to the app's rule.
        assertFalse(viewModel.addRelay("ws://relay.one.example"))

        assertTrue(viewModel.addRelay("wss://relay.one.example"))
        assertFalse(viewModel.addRelay("wss://relay.one.example"))
        assertEquals(EditGroupCuratedSchemaViewModel.AddRelayFailure.AlreadyListed, viewModel.addRelayFailure)
    }

    @Test
    fun `opening a schema and pressing the button proposes nothing`() {
        // The case that has to be silent. A member opening the editor, reading it
        // and pressing the button must not spend a quorum on an identical schema.
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())

        var outcome = ""
        viewModel.proposeSchema(
            localChatRoom = localChatRoom,
            existing = existing(),
            identifier = films.identifier,
            title = films.title,
            name = films.name,
            description = films.description,
            picture = "",
            domain = "",
            onSuccess = { outcome = "proposed" },
            onInvalid = { outcome = "invalid" },
            onNothingToPropose = { outcome = "nothing" },
            onFailure = { outcome = "failed" },
        )

        assertEquals("nothing", outcome)
        assertEquals(emptyList(), viewModel.problems)
    }

    @Test
    fun `a field of a type this build cannot show survives an edit, and is not an edit by itself`() {
        // A schema another client wrote may use a type this build does not know.
        // The editor cannot show it, and must neither sign it away nor mistake
        // its own blind spot for a change the member made.
        val stars = listOf("field", "rating", "stars", "required", "5", "Rating", "{\"max\":5}")
        val withStars = films.copy(
            unsupportedFields = listOf(CuratedUnsupportedField("rating", "stars", isRequired = true, label = "Rating", tag = stars))
        )
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing(withStars))

        var outcome = ""
        viewModel.proposeSchema(
            localChatRoom = localChatRoom,
            existing = existing(withStars),
            identifier = films.identifier,
            title = films.title,
            name = films.name,
            description = films.description,
            picture = "",
            domain = "",
            onSuccess = { outcome = "proposed" },
            onInvalid = { outcome = "invalid" },
            onNothingToPropose = { outcome = "nothing" },
            onFailure = { outcome = "failed" },
        )
        assertEquals("nothing", outcome)

        // And what an edit would sign carries the tag as it came.
        val draft = viewModel.draft(films.identifier, films.title, films.name, films.description, picture = "", domain = "")
        assertEquals(stars, GroupCuratedSchema.template(draft).tags.first { it[0] == "field" && it[1] == "rating" }.toList())
    }

    @Test
    fun `an edit keeps the identifier whatever the field says`() {
        val viewModel = viewModel("films")
        viewModel.seedWorkingCopy(editing())

        var outcome = ""
        viewModel.proposeSchema(
            localChatRoom = localChatRoom,
            existing = existing(),
            // A different identifier would be a different list, not an edit.
            identifier = "books",
            title = films.title,
            name = films.name,
            description = films.description,
            picture = "",
            domain = "",
            onSuccess = { outcome = "proposed" },
            onInvalid = { outcome = "invalid" },
            onNothingToPropose = { outcome = "nothing" },
            onFailure = { outcome = "failed" },
        )

        assertEquals("nothing", outcome, "a changed identifier was taken as an edit")
    }

    @Test
    fun `a schema a reader would refuse is refused here, and the reasons are kept`() {
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(newSchema())

        var outcome = ""
        viewModel.proposeSchema(
            localChatRoom = localChatRoom,
            existing = null,
            identifier = "  ",
            title = "t".repeat(201),
            name = "Films",
            description = "",
            picture = "http://example.com/x.png",
            domain = "not a domain",
            onSuccess = { outcome = "proposed" },
            onInvalid = { outcome = "invalid" },
            onNothingToPropose = { outcome = "nothing" },
            onFailure = { outcome = "failed" },
        )

        assertEquals("invalid", outcome)
        assertEquals(
            listOf(
                CuratedSchemaProblem.IdentifierMissing,
                CuratedSchemaProblem.TitleTooLong,
                CuratedSchemaProblem.DescriptionMissing,
                CuratedSchemaProblem.PictureNotHttps,
                CuratedSchemaProblem.DomainInvalid,
            ),
            viewModel.problems,
        )
    }

    @Test
    fun `the draft trims what was typed and normalizes the domain`() {
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(newSchema())

        val draft = viewModel.draft(
            identifier = " films ",
            title = " Film suggestion ",
            name = " Films ",
            description = " Films. ",
            picture = "  ",
            domain = " https://Example.COM/ ",
        )

        assertEquals("films", draft.identifier)
        assertEquals("Film suggestion", draft.title)
        assertEquals("Films", draft.name)
        assertEquals("Films.", draft.description)
        assertNull(draft.picture)
        assertEquals("example.com", draft.domain)
        assertTrue(draft.isUsable())
    }
}
