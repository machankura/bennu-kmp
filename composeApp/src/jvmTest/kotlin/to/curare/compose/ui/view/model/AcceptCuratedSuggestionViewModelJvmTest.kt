package to.curare.compose.ui.view.model

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalNostrEvent
import to.curare.compose.database.model.types.SynchronizationFilter
import to.curare.compose.nostr.CuratedSuggestion
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.curated.CuratedEntryEvent
import to.curare.compose.nostr.curated.CuratedEntryProblem
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldConfig
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedUnsupportedField
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.AcceptCuratedSuggestionUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The decisions the accept form makes that nothing else can check.
 *
 * **What is opened and what is kept.** The form asks for what the schema's
 * form asks for, and keeps the `d` and the derived fields as they were
 * suggested; getting that wrong in one direction lets a member invent a
 * hashtag the app was supposed to derive, and in the other loses the
 * coordinate that makes a re-curation a revision.
 *
 * **What the button refuses.** An entry the queue would not count is refused
 * with the field named, before a quorum is asked for it -- and the entry it
 * does propose is the one the queue screen will read back, source and all.
 *
 * **What the loader finds.** The suggestion is looked up by the queue's own
 * reading, so what is accepted is what was shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptCuratedSuggestionViewModelJvmTest {
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
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        schema = CuratedSchema(
            identifier = "films",
            title = "Film suggestion",
            name = "Films worth translating",
            description = "Films the group would subtitle.",
            visibility = CuratedVisibility.Public,
            fields = listOf(
                CuratedField.IDENTIFIER,
                CuratedField.TITLE,
                CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year", config = CuratedFieldConfig(min = 1900, max = 2100)),
                CuratedField("type", CuratedFieldType.Enum, isRequired = true, label = "Type", config = CuratedFieldConfig(options = listOf("movie", "documentary"))),
                CuratedField("watchUrl", CuratedFieldType.Url, isRequired = false, label = "Watch URL", config = CuratedFieldConfig(tag = "r", marker = "watch", repeat = true)),
                CuratedField("imdbUrl", CuratedFieldType.Url, isRequired = false, label = "IMDb URL", config = CuratedFieldConfig(tag = "r", marker = "imdb")),
                CuratedField("hashtags", CuratedFieldType.Token, isRequired = false, label = "Hashtags", config = CuratedFieldConfig(tag = "t", repeat = true, derived = true)),
                CuratedField("review", CuratedFieldType.LongText, isRequired = false, label = "Review", config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG)),
            ),
            requireAny = listOf(listOf("watchUrl", "imdbUrl")),
            relays = listOf("wss://relay.one.example"),
        ).normalized(),
    )

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = ada,
            subject = "Translation room",
            description = null,
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = "state"
        )
    )

    /** *Magic Money* as Bob suggested it: two watch links, and hashtags his client derived. */
    private val suggestedEvent = NostrEvent(
        id = "1".repeat(64),
        pubKey = bob,
        kind = CuratedSchemaEvent.SUGGESTION_KIND,
        tags = arrayOf(
            arrayOf("d", "magic-money"),
            arrayOf("title", "Magic Money"),
            arrayOf("year", "2018"),
            arrayOf("type", "documentary"),
            arrayOf("r", "https://example.com/watch", "watch"),
            arrayOf("r", "https://mirror.example/watch", "watch"),
            arrayOf("t", "bitcoin"),
            arrayOf("t", "documentary"),
            arrayOf("a", schema.coordinate(), "", "root"),
        ),
        content = "Follows the early community.",
        sig = "b".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    private val suggestion: CuratedSuggestion = CuratedSuggestion.queueOf(
        schema,
        listOf(LocalNostrEvent(nostrEvent = suggestedEvent, profile = null))
    ).single()

    private fun loaded(canSign: Boolean = true) = AcceptCuratedSuggestionUIState.Loaded(
        localChatRoom = localChatRoom,
        schema = schema,
        suggestion = suggestion,
        canSign = canSign,
    )

    /** What was asked of the signing repository, and the session it answers with. */
    private class RecordingSigning(private val session: FrostSigningSession?) :
        FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var kind: Kind? = null
        var tags: Array<Array<String>>? = null
        var content: String? = null

        override suspend fun canSign(chatRoomId: String): Boolean = true

        override suspend fun proposeSigning(
            localChatRoom: LocalChatRoom,
            userPublicKey: HexKey,
            kind: Kind,
            tags: Array<Array<String>>,
            content: String
        ): FrostSigningSession? {
            this.kind = kind
            this.tags = tags
            this.content = content
            return session
        }
    }

    private fun session() = FrostSigningSession(
        id = "session-1",
        chatRoomId = chatRoomId,
        coordinatorPublicKey = ada,
        userPublicKey = ada,
        dkgSessionId = "dkg-1",
        threshold = 2,
        participantCount = 3,
        signerId = 1,
    )

    private fun viewModel(
        signing: FrostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
        nostrRepository: NostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
        chatRepository: ChatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
        suggestionId: String = suggestion.id,
    ) = AcceptCuratedSuggestionViewModel(
        chatRoomId = chatRoomId,
        activeUserPublicKey = ada,
        relayHint = null,
        identifier = "films",
        suggestionId = suggestionId,
        initialAcceptCuratedSuggestionUIState = AcceptCuratedSuggestionUIState.Loading,
        nostrRepository = nostrRepository,
        chatRepository = chatRepository,
        frostSigningRepository = signing,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the form opens what the schema asks for, one input per value, and keeps the rest`() {
        val state = loaded()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(state)

        // Not the identifier -- it writes to `d` -- and not the derived hashtags.
        assertEquals(
            listOf("title", "year", "type", "watchUrl", "imdbUrl", "review"),
            AcceptCuratedSuggestionViewModel.editableFields(state).map { it.name }
        )
        assertEquals(
            listOf("identifier", "hashtags"),
            AcceptCuratedSuggestionViewModel.keptFields(state).map { it.name }
        )

        assertEquals(listOf("Magic Money"), viewModel.inputs["title"]?.map { it.text.toString() })
        assertEquals(listOf("2018"), viewModel.inputs["year"]?.map { it.text.toString() })
        // A field that repeats gets an input per value the suggestion carried.
        assertEquals(
            listOf("https://example.com/watch", "https://mirror.example/watch"),
            viewModel.inputs["watchUrl"]?.map { it.text.toString() }
        )
        // A field the suggestion has nothing for gets one blank to type into.
        assertEquals(listOf(""), viewModel.inputs["imdbUrl"]?.map { it.text.toString() })
        assertNull(viewModel.inputs["identifier"], "the identifier was opened for editing")
        assertNull(viewModel.inputs["hashtags"], "a derived field was opened for editing")
    }

    @Test
    fun `seeding again leaves an edit alone`() {
        val state = loaded()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(state)
        viewModel.inputs["year"]!![0].setTextAndPlaceCursorAtEnd("2019")

        viewModel.seedWorkingCopy(state)

        assertEquals("2019", viewModel.inputs["year"]!![0].text.toString())
    }

    @Test
    fun `the draft is what was typed plus what was kept, and the d is the suggestion's`() {
        val state = loaded()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(state)

        viewModel.inputs["year"]!![0].setTextAndPlaceCursorAtEnd("2019")
        viewModel.addValue("watchUrl")
        viewModel.inputs["watchUrl"]!![2].setTextAndPlaceCursorAtEnd("https://third.example/watch")
        viewModel.removeValue("watchUrl", 0)

        val draft = viewModel.draft(state)

        assertEquals(listOf("magic-money"), draft["identifier"])
        assertEquals(listOf("2019"), draft["year"])
        assertEquals(listOf("https://mirror.example/watch", "https://third.example/watch"), draft["watchUrl"])
        assertEquals(listOf("bitcoin", "documentary"), draft["hashtags"])
        // A blank input comes through; the builder is what drops it.
        assertEquals(listOf(""), draft["imdbUrl"])
    }

    @Test
    fun `removing the last input leaves a blank one rather than nothing`() {
        val state = loaded()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(state)

        viewModel.removeValue("year", 0)

        assertEquals(listOf(""), viewModel.inputs["year"]?.map { it.text.toString() })
    }

    @Test
    fun `an entry the queue would refuse is refused with the field named, and nothing is proposed`() {
        val state = loaded()
        val signing = RecordingSigning(session())
        val viewModel = viewModel(signing)
        viewModel.seedWorkingCopy(state)

        viewModel.inputs["year"]!![0].setTextAndPlaceCursorAtEnd("1850")
        viewModel.inputs["type"]!![0].setTextAndPlaceCursorAtEnd("musical")

        var invalid = false
        viewModel.proposeEntry(state, onSuccess = { error("proposed") }, onInvalid = { invalid = true }, onFailure = { error("failed") })

        assertTrue(invalid)
        assertEquals(listOf(CuratedEntryProblem.Reason.BelowMinimum), viewModel.problemsFor("year").map { it.reason })
        assertEquals(listOf(CuratedEntryProblem.Reason.NotAnOption), viewModel.problemsFor("type").map { it.reason })
        assertEquals(emptyList(), viewModel.problemsAboutTheEntry(state))
        assertNull(signing.kind, "a refused entry was proposed anyway")
        assertFalse(viewModel.isActionPending.value)
    }

    @Test
    fun `a list with a required field this build cannot write is refused before a quorum is spent`() {
        // The schema was written by a client that knows a type this build does
        // not. The reading is fine without it; an entry written without it is
        // not, and the refusal has no input to sit under, so it goes at the top
        // with the field's own label.
        val stars = listOf("field", "rating", "stars", "required", "5", "Rating", "{}")
        val state = loaded().copy(
            schema = schema.copy(
                schema = schema.schema.copy(
                    unsupportedFields = listOf(CuratedUnsupportedField("rating", "stars", isRequired = true, label = "Rating", tag = stars))
                )
            )
        )
        val signing = RecordingSigning(session())
        val viewModel = viewModel(signing)
        viewModel.seedWorkingCopy(state)

        var invalid = false
        viewModel.proposeEntry(state, onSuccess = { error("proposed") }, onInvalid = { invalid = true }, onFailure = { error("failed") })

        assertTrue(invalid)
        assertEquals(
            listOf(CuratedEntryProblem("rating", CuratedEntryProblem.Reason.UnsupportedType)),
            viewModel.problemsAboutTheEntry(state)
        )
        assertNull(signing.kind, "an entry the list's other readers would refuse was proposed anyway")
    }

    @Test
    fun `a require-any rule the edit broke is filed under its first field`() {
        val state = loaded()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(state)

        viewModel.removeValue("watchUrl", 0)
        viewModel.removeValue("watchUrl", 0)

        viewModel.proposeEntry(state, onSuccess = {}, onInvalid = {}, onFailure = {})

        assertEquals(listOf(CuratedEntryProblem.Reason.RequireAnyUnmet), viewModel.problemsFor("watchUrl").map { it.reason })
    }

    @Test
    fun `the entry proposed is the canonical one the queue will read back`() {
        val state = loaded()
        val signing = RecordingSigning(session())
        val viewModel = viewModel(signing)
        viewModel.seedWorkingCopy(state)
        viewModel.inputs["year"]!![0].setTextAndPlaceCursorAtEnd("2019")

        var sessionId: String? = null
        viewModel.proposeEntry(state, onSuccess = { sessionId = it }, onInvalid = { error("invalid") }, onFailure = { error("failed") })

        runBlocking {
            withTimeout(5_000) {
                while (sessionId == null) kotlinx.coroutines.delay(10)
            }
        }

        assertEquals("session-1", sessionId)
        assertEquals(CuratedEntryEvent.CANONICAL_KIND, signing.kind)
        assertEquals("Follows the early community.", signing.content)

        val tags = assertNotNull(signing.tags).map { it.toList() }
        assertTrue(listOf("d", "magic-money") in tags, "the entry lost the suggestion's d")
        assertTrue(listOf("year", "2019") in tags, "the edit was not signed")
        assertTrue(listOf("t", "bitcoin") in tags && listOf("t", "documentary") in tags, "the derived hashtags were dropped")
        assertTrue(listOf("a", schema.coordinate(), "wss://relay.one.example", "root") in tags, "the entry does not reply to the list")
        assertTrue(listOf("a", "31888:$bob:magic-money", "", "mention") in tags, "the entry does not point at the suggestion")
        assertTrue(listOf("e", suggestion.id, "", "mention") in tags, "the entry does not pin the version read")
        assertTrue(listOf("p", bob) in tags, "the suggester is not credited")

        // And it is what the queue counts as the group's entry for that suggestion.
        val signed = NostrEvent(
            id = "2".repeat(64),
            pubKey = chatRoomId,
            kind = CuratedEntryEvent.CANONICAL_KIND,
            tags = signing.tags!!,
            content = signing.content!!,
            sig = "b".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_900),
        )
        val queue = CuratedSuggestion.queueOf(
            schema,
            listOf(LocalNostrEvent(suggestedEvent, null), LocalNostrEvent(signed, null))
        )
        assertTrue(queue.single().isCurated, "the queue does not count the proposed entry as curating the suggestion")
    }

    @Test
    fun `a session that could not be opened is a failure, and the form stays`() {
        val state = loaded()
        val viewModel = viewModel(RecordingSigning(session = null))
        viewModel.seedWorkingCopy(state)

        var failed = false
        viewModel.proposeEntry(state, onSuccess = { error("proposed") }, onInvalid = { error("invalid") }, onFailure = { failed = true })

        runBlocking {
            withTimeout(5_000) {
                while (!failed) kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(emptyList(), viewModel.problems)
        assertEquals("Magic Money", viewModel.inputs["title"]!![0].text.toString())
    }

    @Test
    fun `the loader finds the suggestion by the queue's reading, and says when it cannot`() {
        val nostrRepository = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
            override suspend fun observeNostrEventsMatching(synchronizationFilter: SynchronizationFilter): Flow<List<LocalNostrEvent>> =
                flowOf(listOf(LocalNostrEvent(nostrEvent = suggestedEvent, profile = null)))
        }
        val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? = localChatRoom
            override suspend fun groupCuratedSchemas(chatRoomId: String): List<GroupCuratedSchema> = listOf(schema)
        }

        val found = viewModel(RecordingSigning(null), nostrRepository, chatRepository)
        found.initiate()
        val loaded = await { found.acceptCuratedSuggestionUIState as? AcceptCuratedSuggestionUIState.Loaded }
        assertEquals("Magic Money", loaded.suggestion.entry.title)
        assertTrue(loaded.canSign)

        val missing = viewModel(RecordingSigning(null), nostrRepository, chatRepository, suggestionId = "9".repeat(64))
        missing.initiate()
        val error = await { missing.acceptCuratedSuggestionUIState as? AcceptCuratedSuggestionUIState.Error }
        assertEquals("Couldn't find that suggestion", error.message)
    }

    private fun <T : Any> await(read: () -> T?): T = runBlocking {
        withTimeout(5_000) {
            var value = read()
            while (value == null) {
                kotlinx.coroutines.delay(10)
                value = read()
            }
            value
        }
    }
}
