package to.curare.compose.ui.view.model

import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalFrostSigningSession
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.view.state.ProposalFilter
import to.curare.compose.ui.view.state.ProposalListUIState
import to.curare.compose.ui.view.state.ProposalState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * What the proposal list's view model decides that nothing else can check.
 *
 * One thing above all, and it is the reason this file exists: the selected
 * filter has to outlive the state it is drawn on. `observeSessionsForChatRoom`
 * re-emits whenever any member's message advances any session in the room, and
 * the view model builds a whole new `Loaded` each time. A `Loaded` built with
 * the field's default would snap the chips back to "all" -- and would do it
 * exactly when somebody else signs, which is exactly when a member is sitting
 * on this screen watching. The bug is invisible in a static list and certain in
 * a busy room, so it is pinned here rather than left to be noticed.
 *
 * The rest is the ordinary shape of the screen: the room is looked up once, a
 * missing room is an error rather than an empty list, and a tap that lands
 * before the first emission is remembered rather than lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProposalListViewModelJvmTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = bob,
            subject = "Translation room",
            description = null,
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = "state",
        )
    )

    private fun chatRepository(room: LocalChatRoom? = localChatRoom) =
        object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
                room.takeIf { id == chatRoomId }
        }

    private fun frostRepository(sessions: Flow<List<LocalFrostSigningSession>>) =
        object : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
            override fun observeSessionsForChatRoom(
                chatRoomId: String
            ): Flow<List<LocalFrostSigningSession>> = sessions
        }

    private fun session(
        id: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        approved: Boolean = false,
        signerIds: String? = null,
    ) = FrostSigningSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = ada,
        userPublicKey = bob,
        dkgSessionId = "ceremony",
        threshold = 2,
        participantCount = 3,
        signerId = 2,
        stage = stage,
        signerIds = signerIds,
        signApprovedAt = Clock.System.now().takeIf { approved },
    )

    /** One session with one readable event in it, as the repository hands it over. */
    private fun local(
        id: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        approved: Boolean = false,
        signerIds: String? = null,
    ) = LocalFrostSigningSession(
        session = session(id, stage, approved, signerIds),
        unorderedItems = listOf(
            FrostSigningItem(
                sessionId = id,
                itemIndex = 0,
                unsignedEventJson = """
                    {"id":"${"e".repeat(64)}","pubkey":"$chatRoomId","created_at":1700000000,
                     "kind":1,"tags":[],"content":"a chapter","sig":""}
                """.trimIndent().replace("\n", ""),
                eventId = "e".repeat(64),
                nonceRandom = "00".repeat(32),
            )
        ),
    )

    /** A room with one of each state in it. */
    private val mixed = listOf(
        local("needs"),
        local("open", approved = true),
        local(
            "signing",
            stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
            approved = true,
            signerIds = "1,2",
        ),
        local("signed", stage = FrostSigningStage.COMPLETE),
        local("gone", stage = FrostSigningStage.FAILED),
    )

    private fun viewModel(
        sessions: Flow<List<LocalFrostSigningSession>>,
        room: LocalChatRoom? = localChatRoom,
        initialFilter: ProposalFilter = ProposalFilter.ALL,
    ) = ProposalListViewModel(
        chatRoomId = chatRoomId,
        initialProposalListUIState = ProposalListUIState.Loading,
        chatRepository = chatRepository(room),
        frostSigningRepository = frostRepository(sessions),
        initialFilter = initialFilter,
    )

    /**
     * Waits for the state to satisfy [predicate], or says what it was waiting for.
     *
     * `initiate` collects on `Dispatchers.IO`, so the wait is a real one rather
     * than a virtual-time one -- the same shape the other view-model tests use.
     */
    private suspend fun ProposalListViewModel.await(
        what: String,
        predicate: (ProposalListUIState) -> Boolean,
    ) {
        try {
            withTimeout(5_000) {
                while (!predicate(proposalListUIState)) delay(10)
            }
        } catch (_: TimeoutCancellationException) {
            fail("timed out waiting for $what; the state was $proposalListUIState")
        }
    }

    private suspend fun ProposalListViewModel.awaitLoaded(): ProposalListUIState.Loaded {
        await("the room to load") { it is ProposalListUIState.Loaded }
        return loaded()
    }

    @Test
    fun `the room's proposals arrive classified, unfiltered by default`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow)
        model.initiate()

        val loaded = model.awaitLoaded()

        assertEquals(ProposalFilter.ALL, loaded.filter)
        assertEquals(5, loaded.visible.size)
        assertEquals(
            listOf(
                ProposalState.NEEDS_YOU,
                ProposalState.WAITING_ON_MEMBERS,
                ProposalState.BEING_SIGNED,
                ProposalState.SIGNED,
                ProposalState.ABANDONED,
            ),
            loaded.proposals.map { it.state }
        )
        assertEquals(1, loaded.counts[ProposalState.NEEDS_YOU])

        model.viewModelScope.cancel()
    }

    @Test
    fun `selecting a filter narrows the rows at once, without waiting for the room to move`() =
        runBlocking {
            val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
            flow.emit(mixed)

            val model = viewModel(flow)
            model.initiate()
            model.awaitLoaded()

            model.select(ProposalFilter.SIGNED)

            val loaded = assertIs<ProposalListUIState.Loaded>(model.proposalListUIState)
            assertEquals(ProposalFilter.SIGNED, loaded.filter)
            assertEquals(listOf("signed"), loaded.visible.map { it.session.id })
            assertEquals(5, loaded.proposals.size, "the room is still whole behind the filter")

            model.viewModelScope.cancel()
        }

    /**
     * The one this file is for.
     *
     * A second emission is what happens when somebody else in the room signs,
     * declines or proposes. It must not take the reader's filter with it.
     */
    @Test
    fun `a session advancing elsewhere in the room does not reset the filter`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow)
        model.initiate()
        model.awaitLoaded()

        model.select(ProposalFilter.NEEDS_YOU)
        assertEquals(listOf("needs"), model.loaded().visible.map { it.session.id })

        // The coordinator fixes the signer set on the open one, and a fresh
        // proposal arrives. The first is a move between two chips, which is the
        // emission most likely to arrive while somebody is reading.
        flow.emit(
            mixed.map {
                if (it.session.id == "open") {
                    local(
                        "open",
                        FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES,
                        approved = true,
                        signerIds = "1,2",
                    )
                } else {
                    it
                }
            } + local("fresh")
        )

        model.await("the new proposal") {
            it is ProposalListUIState.Loaded && it.proposals.size == 6
        }

        val after = model.loaded()
        assertEquals(ProposalFilter.NEEDS_YOU, after.filter, "the filter was reset by an emission")
        assertEquals(
            listOf("needs", "fresh"),
            after.visible.map { it.session.id },
            "the filter was kept but not applied to the new rows"
        )
        assertEquals(
            0,
            after.counts[ProposalState.WAITING_ON_MEMBERS],
            "the counts did not follow the room"
        )
        assertEquals(2, after.counts[ProposalState.BEING_SIGNED])

        model.viewModelScope.cancel()
    }

    /**
     * Working through the queue empties the filter under the reader, and that is
     * the intended end of it: the rows go as they are answered and what is left
     * is the empty state saying nothing is waiting. The counts behind it still
     * show where everything went.
     */
    @Test
    fun `answering the last proposal in a filter empties it rather than widening it`() =
        runBlocking {
            val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
            flow.emit(listOf(local("needs")))

            val model = viewModel(flow)
            model.initiate()
            model.awaitLoaded()

            model.select(ProposalFilter.NEEDS_YOU)
            assertEquals(1, model.loaded().visible.size)

            flow.emit(listOf(local("needs", approved = true)))

            model.await("the answer to land") {
                it is ProposalListUIState.Loaded &&
                    it.counts[ProposalState.NEEDS_YOU] == 0
            }

            val after = model.loaded()
            assertEquals(ProposalFilter.NEEDS_YOU, after.filter)
            assertTrue(after.visible.isEmpty())
            assertEquals(1, after.proposals.size)
            assertEquals(1, after.counts[ProposalState.WAITING_ON_MEMBERS])

            model.viewModelScope.cancel()
        }

    /**
     * A filter chosen before the screen has loaded, which is what a deep link
     * into the queue is: the first emission is already filtered, so the reader
     * never sees the unfiltered list flash past.
     */
    @Test
    fun `an initial filter applies to the very first emission`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow, initialFilter = ProposalFilter.NEEDS_YOU)
        model.initiate()

        val loaded = model.awaitLoaded()

        assertEquals(ProposalFilter.NEEDS_YOU, loaded.filter)
        assertEquals(listOf("needs"), loaded.visible.map { it.session.id })

        model.viewModelScope.cancel()
    }

    /** A tap that lands while the room is still being looked up is remembered. */
    @Test
    fun `selecting before the first emission is not lost`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)

        val model = viewModel(flow)
        model.initiate()

        model.select(ProposalFilter.ABANDONED)
        flow.emit(mixed)

        val loaded = model.awaitLoaded()

        assertEquals(ProposalFilter.ABANDONED, loaded.filter)
        assertEquals(listOf("gone"), loaded.visible.map { it.session.id })

        model.viewModelScope.cancel()
    }

    @Test
    fun `a room that cannot be found is an error, not an empty list`() = runBlocking {
        val flow = MutableSharedFlow<List<LocalFrostSigningSession>>(replay = 1)
        flow.emit(mixed)

        val model = viewModel(flow, room = null)
        model.initiate()

        model.await("the error") { it is ProposalListUIState.Error }
        assertIs<ProposalListUIState.Error>(model.proposalListUIState)

        model.viewModelScope.cancel()
    }

    private fun ProposalListViewModel.loaded(): ProposalListUIState.Loaded =
        assertIs<ProposalListUIState.Loaded>(proposalListUIState)
}
