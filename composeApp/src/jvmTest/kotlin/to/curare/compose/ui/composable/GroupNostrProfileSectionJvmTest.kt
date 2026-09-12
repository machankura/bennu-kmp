package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupKeyState
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.GroupCuratedEntry
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupNostrProfile
import to.curare.compose.nostr.GroupPost
import to.curare.compose.nostr.GroupRelay
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedEntryEvent
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedSuggestionRef
import to.curare.compose.nostr.curated.CuratedVisibility
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.MantraRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.ChatRoomDetailUIState
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Where the group's nostr profile sits, and when it is there at all.
 *
 * The section's contents need no test -- a name and a line of prose in a
 * `ListItem` -- but two things about it do, and neither is settled by reading the
 * screen top to bottom.
 *
 * **Its place in the order.** It goes under what the room *is* -- the key it signs
 * as and whose child it is -- and above what the room has *made*, because a
 * profile is part of the identity rather than part of the work. That ordering is
 * an intention that a later `item {}` inserted in the wrong place would silently
 * undo, and the screen is long enough that nobody would notice.
 *
 * **Its absence in a NIP-17 room.** A NIP-17 room's id is a conversation, not a
 * key it can sign as, so it has no nostr identity and never will. An empty section
 * there would promise something that is not coming, which is worse than saying
 * nothing.
 */
@OptIn(ExperimentalTestApi::class)
class GroupNostrProfileSectionJvmTest {

    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val profile = GroupNostrProfile(
        signedEvent = GroupSignedEvent(
            id = "e".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = MetadataEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
        // Built rather than read out of the event: the reading checks a real
        // signature, and this test is about the screen rather than about that.
        metadata = UserMetadata().apply {
            displayName = "Translation collective"
            about = "A group translating hard books into Sesotho."
            nip05 = "group@example.com"
        }
    )

    @Test
    fun `the profile sits under the signing key and above the group's work`() = render(
        state(groupNostrProfile = profile)
    ) {
        val signingKey = onNodeWithText("Signing key").getBoundsInRoot().top
        val nostrProfile = onNodeWithText("Nostr profile").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(
            signingKey < nostrProfile,
            "the nostr profile section climbed above the signing key row"
        )
        assertTrue(
            nostrProfile < subgroups,
            "the nostr profile section fell below the group's work"
        )

        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("group@example.com").assertIsDisplayed()
    }

    @Test
    fun `the relay lists sit under the profile and above the group's work`() = render(
        state(groupNostrProfile = profile, relayLists = signedRelayLists())
    ) {
        val nostrProfile = onNodeWithText("Nostr profile").getBoundsInRoot().top
        val relays = onNodeWithText("Relays").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(nostrProfile < relays, "the relay lists climbed above the profile")
        assertTrue(relays < subgroups, "the relay lists fell below the group's work")

        // Every set gets a row whatever the group has said, and the two absences
        // read differently: one has never been agreed, the other was agreed empty.
        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("relay.one.example").assertIsDisplayed()
        onNodeWithText("Messages").assertIsDisplayed()
        onNodeWithText("Search").assertIsDisplayed()
        onNodeWithText("Blocked").assertIsDisplayed()
        // Two of them, because Messages and Search are both unagreed -- and the
        // count is the assertion: a row per set, not a row per set the group has
        // got round to.
        onAllNodesWithText("Not set yet").assertCountEquals(2)
        onNodeWithText("No relays in this list").assertIsDisplayed()
        onNodeWithText("Edit relays").assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key reads the relays and is offered no edit`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            canEditNostrProfile = false
        )
    ) {
        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("relay.one.example").assertIsDisplayed()
        onNodeWithText("Edit relays").assertDoesNotExist()
    }

    @Test
    fun `a member holding no share of the key reads the posts and is offered no composer`() =
        render(
            state(
                groupNostrProfile = profile,
                posts = listOf(post("Something the group said", 1_700_000_000)),
                canEditNostrProfile = false
            )
        ) {
            // A post is the one thing here that is meant to be read by people who
            // were not in the room, so a member without a share reads it like
            // anybody else and simply cannot propose the next one.
            onNodeWithText("Something the group said").assertIsDisplayed()
            onNodeWithText("Add post").assertDoesNotExist()
        }

    @Test
    fun `the posts sit under the relay lists and above the group's work`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            posts = listOf(post("The second thing", 1_700_000_900), post("The first thing", 1_700_000_000))
        )
    ) {
        val relays = onNodeWithText("Relays").getBoundsInRoot().top
        val posts = onNodeWithText("Posts").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(relays < posts, "the posts climbed above the relay lists")
        assertTrue(posts < subgroups, "the posts fell below the group's work")

        // Newest first, and whole rather than previewed -- a post is the one thing
        // on this screen that exists to be read.
        val newest = onNodeWithText("The second thing").getBoundsInRoot().top
        val oldest = onNodeWithText("The first thing").getBoundsInRoot().top
        assertTrue(newest < oldest, "the posts were drawn oldest first")

        // Under the group's name, not the member's. The whole point of signing a
        // post as the group is that no member can be blamed for it.
        onAllNodesWithText("Translation collective").assertCountEquals(3)
        onNodeWithText("Add post").assertIsDisplayed()
    }

    @Test
    fun `a group that has posted nothing says so, and offers to post`() = render(
        state(groupNostrProfile = profile, relayLists = signedRelayLists())
    ) {
        onNodeWithText("Posts").assertIsDisplayed()
        onNodeWithText("This group hasn't posted anything yet.").assertIsDisplayed()
        onNodeWithText("Add post").assertIsDisplayed()
    }

    @Test
    fun `the curated schemas sit under the posts and above the group's work`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            posts = listOf(post("Something the group said", 1_700_000_000)),
            schemas = listOf(
                schema("films", "Films worth translating", 1_700_000_900),
                schema("books", "Books worth translating", 1_700_000_000)
            )
        )
    ) {
        val posts = onNodeWithText("Posts").getBoundsInRoot().top
        val schemas = onNodeWithText("Curated schemas").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(posts < schemas, "the curated schemas climbed above the posts")
        assertTrue(schemas < subgroups, "the curated schemas fell below the group's work")

        // Most recently signed first, one card per list.
        val newest = onNodeWithText("Films worth translating").getBoundsInRoot().top
        val oldest = onNodeWithText("Books worth translating").getBoundsInRoot().top
        assertTrue(newest < oldest, "the schemas were drawn oldest first")

        // What an entry asks for, and who may suggest one.
        onAllNodesWithText("Fields: Identifier, Title", substring = true).assertCountEquals(2)
        onAllNodesWithText("Public · ", substring = true).assertCountEquals(2)
        onNodeWithText("Add schema").assertIsDisplayed()
    }

    @Test
    fun `a group that curates nothing says so, and offers to add a schema`() = render(
        state(groupNostrProfile = profile, relayLists = signedRelayLists())
    ) {
        onNodeWithText("Curated schemas").assertIsDisplayed()
        onNodeWithText("This group hasn't published a curated schema yet.", substring = true)
            .assertIsDisplayed()
        onNodeWithText("Add schema").assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key reads the schemas and is offered no editor`() =
        render(
            state(
                groupNostrProfile = profile,
                schemas = listOf(schema("films", "Films worth translating", 1_700_000_000)),
                canEditNostrProfile = false
            )
        ) {
            // A schema exists to be read by people who were never in the room, so
            // a member without a share reads it like anybody else and simply
            // cannot propose a revision -- neither a new one nor an edit.
            onNodeWithText("Films worth translating").assertIsDisplayed()
            onNodeWithText("Add schema").assertDoesNotExist()
            onNodeWithContentDescription("Edit schema").assertDoesNotExist()

            // The queue is the one thing in the block the group did not write,
            // and reading it takes no share of the key.
            onNodeWithText("View suggestions").assertIsDisplayed()
        }

    @Test
    fun `every schema offers its own queue, under its own card`() = render(
        state(
            groupNostrProfile = profile,
            schemas = listOf(
                schema("films", "Films worth translating", 1_700_000_900),
                schema("books", "Books worth translating", 1_700_000_000)
            )
        )
    ) {
        // One per list, because each list has its own queue -- and under the
        // card it belongs to rather than once for the section, since a button
        // between two cards would say nothing about which it opened.
        val buttons = onAllNodesWithText("View suggestions")
        buttons.assertCountEquals(2)

        val films = onNodeWithText("Films worth translating").getBoundsInRoot()
        val books = onNodeWithText("Books worth translating").getBoundsInRoot()
        val first = buttons[0].getBoundsInRoot()
        val second = buttons[1].getBoundsInRoot()

        assertTrue(films.top < first.top && first.top < books.top, "the first queue button is not under its card")
        assertTrue(books.top < second.top, "the second queue button is not under its card")
    }

    @Test
    fun `the curated entries sit under the schemas and above the group's work`() = render(
        state(
            groupNostrProfile = profile,
            schemas = listOf(schema("films", "Films worth translating", 1_700_000_000)),
            entries = listOf(
                entry("The Rise and Rise of Bitcoin", 1_700_000_900),
                entry("Magic Money", 1_700_000_300, suggestedBy = null)
            )
        )
    ) {
        val schemas = onNodeWithText("Curated schemas").getBoundsInRoot().top
        val entries = onNodeWithText("Curated entries").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(schemas < entries, "the curated entries climbed above the schemas")
        assertTrue(entries < subgroups, "the curated entries fell below the group's work")

        // Most recently signed first, one card per entry.
        val newest = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot().top
        val oldest = onNodeWithText("Magic Money").getBoundsInRoot().top
        assertTrue(newest < oldest, "the entries were drawn oldest first")

        // Which list, what the entry is -- labelled as the schema labels it --
        // and who suggested it, where somebody did.
        onAllNodesWithText("In Films worth translating").assertCountEquals(2)
        onAllNodesWithText("Year: 2014", substring = true).assertCountEquals(2)
        onAllNodesWithText("Suggested by eebb74ab · ", substring = true).assertCountEquals(1)
        // Dated like every signed statement in the block: the profile, the
        // schema, and the two entries.
        onAllNodesWithText("Signed by the group on", substring = true).assertCountEquals(4)
    }

    @Test
    fun `a group that has accepted nothing says so, and offers no way to add one here`() = render(
        state(
            groupNostrProfile = profile,
            schemas = listOf(schema("films", "Films worth translating", 1_700_000_000))
        )
    ) {
        onNodeWithText("Curated entries").assertIsDisplayed()
        onNodeWithText("This group hasn't accepted anything into a list yet.", substring = true)
            .assertIsDisplayed()

        // An entry is accepted from the queue on the schema's card, not from
        // this section, so the section has no button of its own.
        onNodeWithText("View suggestions").assertIsDisplayed()
        onAllNodesWithText("Add entry").assertCountEquals(0)
    }

    @Test
    fun `every accepted entry offers a broadcast under its own card, share or no share`() = render(
        state(
            groupNostrProfile = profile,
            schemas = listOf(schema("films", "Films worth translating", 1_700_000_000)),
            entries = listOf(entry("The Rise and Rise of Bitcoin", 1_700_000_900)),
            canEditNostrProfile = false
        )
    ) {
        // The profile's, the schema's and the entry's: the entry is the one
        // event in the block whose broadcast the queue screen depends on, since
        // that is where the "Curated" mark is read back from.
        val buttons = onAllNodesWithText("Broadcast")
        buttons.assertCountEquals(3)

        val schemaCard = onNodeWithText("Films worth translating").getBoundsInRoot().top
        val entryCard = onNodeWithText("The Rise and Rise of Bitcoin").getBoundsInRoot().top
        val last = buttons[2].getBoundsInRoot().top

        assertTrue(schemaCard < entryCard, "the entry climbed above its schema")
        assertTrue(entryCard < last, "the entry's broadcast is not under its card")
    }

    @Test
    fun `every signed event in the block offers a broadcast, under its own card`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            posts = listOf(post("Something the group said", 1_700_000_000)),
            schemas = listOf(schema("films", "Films worth translating", 1_700_000_900))
        )
    ) {
        // One per signed event: the profile, the two relay lists the group has
        // agreed -- the withdrawn one included, since a withdrawal is a statement
        // relays still holding the old list need to hear -- the post and the
        // schema. Not the two lists the group has never agreed, which are not
        // events.
        val buttons = onAllNodesWithText("Broadcast")
        buttons.assertCountEquals(5)

        // Under the card it sends, in the order the block lays them out. The
        // profile card is found by its address, since its name is also the
        // post's byline.
        val profileCard = onNodeWithText("group@example.com").getBoundsInRoot().top
        val relays = onNodeWithText("Relays").getBoundsInRoot().top
        val general = onNodeWithText("General").getBoundsInRoot().top
        val blocked = onNodeWithText("Blocked").getBoundsInRoot().top
        val postCard = onNodeWithText("Something the group said").getBoundsInRoot().top
        val schemaCard = onNodeWithText("Films worth translating").getBoundsInRoot().top
        val tops = (0 until 5).map { buttons[it].getBoundsInRoot().top }

        assertTrue(profileCard < tops[0] && tops[0] < relays, "the profile's broadcast is not under its card")
        assertTrue(general < tops[1] && tops[1] < blocked, "the general list's broadcast is not under its row")
        assertTrue(blocked < tops[2] && tops[2] < postCard, "the blocked list's broadcast is not under its row")
        assertTrue(postCard < tops[3] && tops[3] < schemaCard, "the post's broadcast is not under its card")
        assertTrue(schemaCard < tops[4], "the schema's broadcast is not under its card")
    }

    @Test
    fun `a member holding no share of the key may still broadcast what the group signed`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            posts = listOf(post("Something the group said", 1_700_000_000)),
            canEditNostrProfile = false
        )
    ) {
        // Sending what is already signed takes no share of the key -- the
        // signature is the group's whoever repeats it -- so this is the one
        // action in the block, with the queue, that the gate does not cover.
        onAllNodesWithText("Broadcast").assertCountEquals(4)
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    @Test
    fun `a member holding a share of the key may propose any event the block can show`() = render(
        state(groupNostrProfile = profile, relayLists = signedRelayLists())
    ) {
        // Last in the identity block: after the schemas and before the divider,
        // because it is about the whole block rather than any one section of it.
        val schemas = onNodeWithText("Curated schemas").getBoundsInRoot().top
        val propose = onNodeWithText("Propose event").getBoundsInRoot().top
        val subgroups = onNodeWithText("Subgroups").getBoundsInRoot().top

        assertTrue(schemas < propose, "the paste entry climbed above the schemas")
        assertTrue(propose < subgroups, "the paste entry fell out of the identity block")
    }

    @Test
    fun `a member holding no share of the key is offered no paste`() = render(
        state(groupNostrProfile = profile, canEditNostrProfile = false)
    ) {
        // The same gate as every button in the block: proposing is a signature by
        // the group, and `proposeSigning` throws for a device holding no share.
        onNodeWithText("Propose event").assertDoesNotExist()
    }

    @Test
    fun `a group that has said nothing says so, and offers to say something`() = render(
        state(groupNostrProfile = null)
    ) {
        onNodeWithText("Nostr profile").assertIsDisplayed()
        onNodeWithText("This group hasn't said anything about itself on Nostr yet.")
            .assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key reads the profile and is offered no edit`() = render(
        state(groupNostrProfile = profile, canEditNostrProfile = false)
    ) {
        // The profile is worth reading whoever is looking; proposing one needs a
        // share, and `proposeSigningBatch` throws without one.
        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    @Test
    fun `a NIP-17 room has no nostr identity and so no section`() = render(
        state(groupNostrProfile = null, mlsGroupState = null, canEditNostrProfile = false)
    ) {
        onNodeWithText("Nostr profile").assertDoesNotExist()
        onNodeWithText("This group hasn't said anything about itself on Nostr yet.")
            .assertDoesNotExist()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()

        // The relay lists, the posts and the schemas go with it: all four
        // describe a nostr identity this room does not have.
        onNodeWithText("Relays").assertDoesNotExist()
        onNodeWithText("Edit relays").assertDoesNotExist()
        onNodeWithText("Posts").assertDoesNotExist()
        onNodeWithText("Add post").assertDoesNotExist()
        onNodeWithText("Curated schemas").assertDoesNotExist()
        onNodeWithText("Add schema").assertDoesNotExist()
        onNodeWithText("Propose event").assertDoesNotExist()
        onAllNodesWithText("Broadcast").assertCountEquals(0)

        // The rest of the screen is untouched, so the section's absence is an
        // absence rather than a screen that failed to draw.
        onNodeWithText("Subgroups").assertIsDisplayed()
    }

    /**
     * Two agreed sets and two absences, which is the only fixture that exercises
     * the three things a row can say: a list, "not set yet", and "no relays".
     */
    private fun signedRelayLists(): List<GroupRelayList> = GroupRelaySet.entries.map { set ->
        when (set) {
            GroupRelaySet.General -> GroupRelayList(
                set = set,
                signedEvent = relaySignedEvent(set),
                relays = listOf(GroupRelay(RelayUrlNormalizer.normalize("wss://relay.one.example")))
            )
            // Agreed and empty: the group withdrew its list rather than never
            // having had one.
            GroupRelaySet.Blocked -> GroupRelayList(
                set = set,
                signedEvent = relaySignedEvent(set),
                relays = emptyList()
            )
            else -> GroupRelayList(set = set, signedEvent = null, relays = emptyList())
        }
    }

    private fun relaySignedEvent(set: GroupRelaySet) = GroupSignedEvent(
        id = "a" + set.kind.toString().padStart(5, '0') + "0".repeat(58),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = set.kind,
        tags = emptyArray(),
        content = "",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    private fun post(content: String, createdAt: Long) = GroupPost(
        signedEvent = GroupSignedEvent(
            id = createdAt.toString().padStart(64, 'b'),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = TextNoteEvent.KIND,
            tags = emptyArray(),
            content = content,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt)
        )
    )

    /**
     * A list the group curates, built rather than read out of a signed event:
     * the reading checks a real signature, and this test is about the screen.
     */
    private fun schema(identifier: String, name: String, createdAt: Long) = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = createdAt.toString().padStart(64, 'c'),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt)
        ),
        schema = CuratedSchema(
            identifier = identifier,
            title = "$name suggestion",
            name = name,
            description = "Things the group would translate, with a link to each.",
            visibility = CuratedVisibility.Public,
            fields = listOf(CuratedField.IDENTIFIER, CuratedField.TITLE),
            relays = listOf("wss://relay.one.example")
        )
    )

    /**
     * An entry the group accepted into the films list, built rather than read
     * out of a signed event, for the reason [schema] is. The list's schema has a
     * year, so the card has a glance to give.
     */
    private fun entry(
        title: String,
        createdAt: Long,
        suggestedBy: String? = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"
    ): GroupCuratedEntry {
        val list = schema("films", "Films worth translating", 1_700_000_000).let { films ->
            films.copy(
                schema = films.schema.copy(
                    fields = films.schema.fields + CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year")
                )
            )
        }
        val identifier = title.lowercase().replace(' ', '-')

        return GroupCuratedEntry(
            signedEvent = GroupSignedEvent(
                id = createdAt.toString().padStart(64, 'd'),
                chatRoomId = chatRoomId,
                publicKey = chatRoomId,
                kind = CuratedEntryEvent.CANONICAL_KIND,
                tags = arrayOf(arrayOf("d", identifier), arrayOf("title", title)),
                content = "",
                signature = "f".repeat(128),
                createdAt = Instant.fromEpochSeconds(createdAt)
            ),
            list = list,
            entry = CuratedEntry(
                kind = CuratedEntryEvent.CANONICAL_KIND,
                id = createdAt.toString().padStart(64, 'd'),
                pubKey = chatRoomId,
                createdAt = createdAt,
                identifier = identifier,
                title = title,
                values = mapOf(
                    "identifier" to listOf(identifier),
                    "title" to listOf(title),
                    "year" to listOf("2014"),
                ),
                source = suggestedBy?.let {
                    CuratedSuggestionRef(id = "a".repeat(64), coordinate = "31888:$it:$identifier", pubKey = it)
                }
            )
        )
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
        localChatRoom = LocalChatRoom(
            chatRoom = ChatRoom(
                id = chatRoomId,
                userPublicKey = "a".repeat(64),
                // Deliberately not the profile's name. The room's subject is this
                // device's name for it and the profile is the group's own, and a
                // test that used one string for both could not tell them apart.
                subject = "Translation room",
                description = "A group translating hard books.",
                initialGiftWrapPayloadId = "sdfaer",
                mlsGroupState = mlsGroupState
            )
        ),
        // A key state, so the signing key row the section has to sit under is on
        // the screen to be measured against. Not a real key: nothing here derives.
        groupKeyState = GroupKeyState(
            chatRoomId = chatRoomId,
            dkgSessionId = "c".repeat(64),
            thresholdPublicKey = "02".padEnd(66, 'a'),
            derivationPath = "m/9420/0/0",
            announcedBy = chatRoomId,
            announcedAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
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
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = "a".repeat(64),
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                            onNavigateToRoute = {},
                            onPopBackToRoute = {}
                        )
                    }
                }
            }
        }

        assertions()
    }
}
