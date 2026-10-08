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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.text.ProposedEvent
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import to.curare.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.ProvideSnackbarHost
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.state.ProposalFilter
import to.curare.compose.ui.view.state.ProposalListUIState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The chip row over the proposal list: what it offers, what it counts, and what
 * tapping one does to the rows beneath it.
 *
 * **Every state is offered, with its own number.** A chip is present at zero
 * too, which is the decision worth testing rather than the obvious one: the
 * alternative is a row that gains and loses chips as sessions advance, and
 * sessions advance while this screen is open -- somebody else signing is exactly
 * when a member comes to look at it.
 *
 * **A filter flattens the screen.** Unfiltered, the two sections stay, because
 * what is waiting on the reader reads differently from what the group has done.
 * Filtered, the headings go: the reader has already said which they want by
 * tapping, and heading a single-state list with "waiting for you" would answer a
 * question nobody asked.
 *
 * **An empty filter says which emptiness it is.** One shared "nothing found" is
 * how five different absences here came to say one thing. A group never asked to
 * sign and a queue the reader has worked through are not the same fact, and the
 * second is not an absence at all.
 */
@OptIn(ExperimentalTestApi::class)
class ProposalListScreenJvmTest {
    private val session = FrostSigningSession(
        id = "needs",
        chatRoomId = chatRoomId,
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = activeUserPublicKey,
        dkgSessionId = "ceremony",
        threshold = 2,
        participantCount = 3,
        signerId = 2,
    )

    private fun proposal(
        id: String,
        label: String,
        detail: String,
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        awaitsYou: Boolean = false,
        approved: Boolean = false,
    ) = ProposalListUIState.Proposal(
        session = session.copy(
            id = id,
            stage = stage,
            signApprovedAt = Clock.System.now().takeIf { approved },
        ),
        proposerName = "Ada",
        signs = listOf(ProposedEvent.Summary(label, detail)),
        awaitsYou = awaitsYou,
    )

    private val needsYou = proposal("needs", "New chapter", "Genesis 1", awaitsYou = true)
    private val alsoNeedsYou = proposal("also", "New chapter", "Genesis 2", awaitsYou = true)
    private val open = proposal("open", "New translation", "isiZulu", approved = true)
    private val signed = proposal("signed", "New post", "A note", stage = FrostSigningStage.COMPLETE)

    private val mixed = listOf(needsYou, alsoNeedsYou, open, signed)

    /**
     * Scrolls a chip into view and returns it.
     *
     * The row scrolls sideways by design -- five counted chips do not fit the
     * sixty characters `readableContent` holds the column to -- so reaching the
     * last one is what the reader does as well.
     */
    private fun ComposeUiTest.chip(label: String) =
        onNodeWithText(label).performScrollTo().assertIsDisplayed()

    @Test
    fun `every state is offered with its own count, zero included`() = render(loaded(mixed)) {
        // Two in the queue, one in progress, one signed -- and nothing abandoned,
        // which still gets a chip rather than vanishing.
        chip("All")
        chip("Needs you · 2")
        chip("In progress · 1")
        chip("Signed · 1")
        chip("Abandoned · 0")
    }

    @Test
    fun `the chips are offered above the rows, in the order the states run`() =
        render(loaded(mixed)) {
            val chips = listOf("All", "Needs you · 2", "In progress · 1", "Signed · 1", "Abandoned · 0")
                .map { onNodeWithText(it).getBoundsInRoot().left }

            assertTrue(
                chips.zipWithNext().all { (left, right) -> left < right },
                "the chips were drawn out of order: $chips"
            )

            val chipBottom = onNodeWithText("All").getBoundsInRoot().bottom
            val firstRow = onNodeWithText("Genesis 1").getBoundsInRoot().top
            assertTrue(chipBottom <= firstRow, "the chips were drawn over the rows")
        }

    @Test
    fun `unfiltered, the sections stay and every proposal is drawn`() = render(loaded(mixed)) {
        onNodeWithText("Waiting for you").assertIsDisplayed()
        onNodeWithText("Everything else").assertIsDisplayed()

        onNodeWithText("Genesis 1").assertIsDisplayed()
        onNodeWithText("Genesis 2").assertIsDisplayed()
        onNodeWithText("isiZulu").assertIsDisplayed()
        onNodeWithText("A note").assertIsDisplayed()
    }

    @Test
    fun `tapping a chip narrows the rows to its state`() = render(loaded(mixed)) {
        chip("Signed · 1").performClick()

        onNodeWithText("A note").assertIsDisplayed()
        onAllNodesWithText("Genesis 1").assertCountEquals(0)
        onAllNodesWithText("Genesis 2").assertCountEquals(0)
        onAllNodesWithText("isiZulu").assertCountEquals(0)
    }

    @Test
    fun `a filtered list drops the section headings`() = render(loaded(mixed)) {
        chip("Needs you · 2").performClick()

        onNodeWithText("Genesis 1").assertIsDisplayed()
        onNodeWithText("Genesis 2").assertIsDisplayed()
        onAllNodesWithText("Waiting for you").assertCountEquals(0)
        onAllNodesWithText("Everything else").assertCountEquals(0)
    }

    @Test
    fun `going back to all restores the sections and the rest of the rows`() =
        render(loaded(mixed)) {
            chip("Signed · 1").performClick()
            onAllNodesWithText("Genesis 1").assertCountEquals(0)

            chip("All").performClick()

            onNodeWithText("Waiting for you").assertIsDisplayed()
            onNodeWithText("Genesis 1").assertIsDisplayed()
            onNodeWithText("A note").assertIsDisplayed()
        }

    /** The counts are the room's, so narrowing the rows must not narrow them. */
    @Test
    fun `the counts stay put when the filter moves`() = render(loaded(mixed)) {
        chip("Signed · 1").performClick()

        chip("Needs you · 2")
        chip("In progress · 1")
        chip("Abandoned · 0")
    }

    @Test
    fun `a filter with nothing in it says which emptiness it is`() = render(loaded(mixed)) {
        chip("Abandoned · 0").performClick()

        onNodeWithText("No proposal has been abandoned.").assertIsDisplayed()
        // Not the whole-screen message, which would be a different and false claim.
        onAllNodesWithText("This group has not been asked to sign anything yet.")
            .assertCountEquals(0)
    }

    /**
     * The queue running dry is the end of the reader's work, not a blank screen,
     * and the chips behind it still say where everything went.
     */
    @Test
    fun `a worked-through queue reads as finished rather than as missing`() =
        render(loaded(listOf(open, signed))) {
            chip("Needs you · 0").performClick()

            onNodeWithText("Nothing is waiting for your signature.").assertIsDisplayed()
            chip("In progress · 1")
            chip("Signed · 1")
        }

    @Test
    fun `each filter has its own words for being empty`() {
        render(loaded(listOf(needsYou))) {
            chip("In progress · 0").performClick()
            onNodeWithText("No proposal is in progress.").assertIsDisplayed()
        }

        render(loaded(listOf(needsYou))) {
            chip("Signed · 0").performClick()
            onNodeWithText("This group has not signed anything yet.").assertIsDisplayed()
        }
    }

    /**
     * A room with nothing in it has nothing to filter, and five chips reading 0
     * would be five ways of saying what the empty state says once.
     */
    @Test
    fun `an untouched room offers no chips at all, only the reason`() = render(loaded(emptyList())) {
        onNodeWithText("This group has not been asked to sign anything yet.").assertIsDisplayed()

        onAllNodesWithText("All").assertCountEquals(0)
        onAllNodesWithText("Needs you · 0").assertCountEquals(0)
        onAllNodesWithText("Abandoned · 0").assertCountEquals(0)
    }

    @Test
    fun `an initial filter is the one selected when the screen opens`() =
        render(loaded(mixed), initialFilter = ProposalFilter.NEEDS_YOU) {
            onNodeWithText("Genesis 1").assertIsDisplayed()
            onNodeWithText("Genesis 2").assertIsDisplayed()
            onAllNodesWithText("A note").assertCountEquals(0)
            onAllNodesWithText("Waiting for you").assertCountEquals(0)
        }

    @Test
    fun `a pushed screen keeps its way back, chips or no chips`() {
        render(loaded(mixed)) { onNodeWithContentDescription("Back").assertIsDisplayed() }
        render(loaded(emptyList())) { onNodeWithContentDescription("Back").assertIsDisplayed() }
    }

    @Test
    fun `the room that could not be found says so, with nothing to filter`() {
        render(ProposalListUIState.Error("Couldn't find the group")) {
            onNodeWithText("Couldn't find the group").assertIsDisplayed()
            onAllNodesWithText("All").assertCountEquals(0)
            // Nothing to retry, so nothing offering to.
            onAllNodesWithText("Try again").assertCountEquals(0)
        }
    }

    private fun loaded(proposals: List<ProposalListUIState.Proposal>) =
        ProposalListUIState.Loaded(
            localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
            proposals = proposals,
        )

    private fun render(
        uiState: ProposalListUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        initialFilter: ProposalFilter = ProposalFilter.ALL,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 500, height = 1600) {
        setContent {
            CurareTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ProposalListScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            initialProposalListUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            frostSigningRepository =
                                FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                            onNavigateBack = {},
                            onNavigateToRoute = onNavigateToRoute,
                            initialFilter = initialFilter,
                        )
                    }
                }
            }
        }

        assertions()
    }
}
