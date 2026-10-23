package com.machankura.bennu.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.DkgSession
import com.machankura.bennu.compose.database.model.MarmotKeyPackage
import com.machankura.bennu.compose.database.model.Participant
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.intermdiate.LocalParticipant
import com.machankura.bennu.compose.database.model.types.DkgRitualStage
import com.machankura.bennu.compose.nostr.MarmotProfile
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.DkgRepository
import com.machankura.bennu.compose.ui.view.state.SelectSubgroupAdminsUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Trying a failed subgroup ceremony again starts from what it was.
 *
 * The ceremony screen used to retry any failed ceremony by opening a group's own
 * -- in a parent's room, a whole-room ceremony that was never the subgroup at
 * all. It now sends a subgroup's back to this picker, and the picker has to
 * start from the name, the admins and the quorum that failed. See
 * docs/ceremony-creation.md, finding F7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectSubgroupAdminsViewModelJvmTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val parentChatRoomId = "9a7e".repeat(16)
    private val failedId = "fa11".repeat(16)

    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val cleo = "c".repeat(64)

    /** Was an admin of the failed ceremony, and has since left the parent. */
    private val dora = "d".repeat(64)

    private val parent = LocalChatRoom(
        chatRoom = ChatRoom(
            id = parentChatRoomId,
            userPublicKey = ada,
            subject = "#Checkers",
            description = null,
            mlsGroupState = "state",
        ),
        localParticipants = listOf(ada, bob, cleo).map {
            LocalParticipant(Participant(participantPublicKey = it, chatRoomId = parentChatRoomId, relayHint = null), null)
        },
    )

    private fun failed(parentChatRoomId: String? = this.parentChatRoomId) = DkgSession(
        id = failedId,
        chatRoomId = this.parentChatRoomId,
        coordinatorPublicKey = ada,
        userPublicKey = ada,
        threshold = 3,
        participantCount = 4,
        participantPublicKeys = DkgSession.formatParticipants(setOf(ada, bob, cleo, dora)),
        subject = "Tapscript",
        stage = DkgRitualStage.FAILED,
        hostPublicKey = ada,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        parentChatRoomId = parentChatRoomId,
    )

    private val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
            parent.takeIf { id == parentChatRoomId }

        // Never answers, so the lookup is still out while the test looks: what is
        // under test is what the form starts from, not who has a key package.
        override suspend fun observeMarmotKeyPackageForPublicKey(
            publicKey: HexKey,
            roomProfile: MarmotProfile,
        ): Flow<MarmotKeyPackage?> = flow { awaitCancellation() }
    }

    private fun viewModel(session: DkgSession?, retrying: String? = failedId) = SelectSubgroupAdminsViewModel(
        activeUserPublicKey = ada,
        parentChatRoomId = parentChatRoomId,
        retrying = retrying,
        initialUIState = SelectSubgroupAdminsUIState.Loading,
        activeIdentityStateFlow = MutableStateFlow(null),
        chatRepository = chatRepository,
        dkgRepository = object : DkgRepository by DkgRepository.NO_OP_DKG_REPOSITORY {
            override fun observeSessionById(sessionId: String): Flow<DkgSession?> =
                flowOf(session?.takeIf { it.id == sessionId })
        },
    )

    private suspend fun SelectSubgroupAdminsViewModel.awaitLoaded() = withTimeout(5_000) {
        while (uiState !is SelectSubgroupAdminsUIState.Loaded) delay(10)
        delay(50)
    }

    @Test
    fun `a retry starts from the failed ceremony's name, admins and quorum`(): Unit = runBlocking {
        val viewModel = viewModel(failed())
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals("Tapscript", viewModel.name.value)
        assertEquals(
            setOf(bob, cleo),
            viewModel.selectedPublicKeys.toSet(),
            "everyone it was with who is still in the parent, the coordinator not among them",
        )
        assertEquals(3, viewModel.threshold.value)
    }

    @Test
    fun `a member's edits survive the screen coming back into view`(): Unit = runBlocking {
        val viewModel = viewModel(failed())
        viewModel.initiate()
        viewModel.awaitLoaded()

        viewModel.setName("Tapscript II")
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals("Tapscript II", viewModel.name.value)
    }

    @Test
    fun `a ceremony that was not this parent's subgroup fills nothing in`(): Unit = runBlocking {
        val viewModel = viewModel(failed(parentChatRoomId = null))
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals("", viewModel.name.value)
        assertEquals(emptySet(), viewModel.selectedPublicKeys.toSet())
    }
}
