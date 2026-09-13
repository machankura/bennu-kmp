package to.curare.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import to.curare.compose.database.GENESIS_AT
import to.curare.compose.database.model.NegentropySynchronizeRequest
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.SynchronizeNostrEventRequest
import to.curare.compose.nostr.DirectMessagePeerSync
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.ProfilePreviewUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * What the preview shows while the relays are asked, and when it stops waiting.
 *
 * The repository fake records every ask and holds the profile row in a
 * `MutableStateFlow`, so the test can play the row arriving before the ask, after
 * it, after the timeout, or never. The view model launches on `Dispatchers.IO`, as
 * the others do, so the timeout is real time cut to a few hundred milliseconds
 * through its constructor, and the assertions poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePreviewViewModelJvmTest {

    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)

    /** Records the asks; the profile flow is the row the device holds. */
    private class Recorder(
        val profile: MutableStateFlow<Profile?> = MutableStateFlow(null),
        private val refuse: Boolean = false,
    ) : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val effects = mutableListOf<String>()

        override suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?> = profile

        override suspend fun queueSynchronizeNostrEvent(synchronizeNostrEventRequests: List<SynchronizeNostrEventRequest>) {
            if (refuse) throw IllegalStateException("the queue is closed")
            effects += "req:" + synchronizeNostrEventRequests.map { it.purpose }.distinct().joinToString()
        }

        override suspend fun queueNegentropySynchronizeRequest(negentropySynchronizeRequests: List<NegentropySynchronizeRequest>) {
            if (refuse) throw IllegalStateException("the queue is closed")
            effects += "neg:" + negentropySynchronizeRequests.map { it.purpose }.distinct().joinToString()
        }
    }

    private val bothAsks = listOf("req:${DirectMessagePeerSync.PROFILE_PURPOSE}", "neg:${DirectMessagePeerSync.PURPOSE}")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(recorder: Recorder) = ProfilePreviewViewModel(
        activeUserPublicKey = me,
        profilePublicKey = alice,
        initialProfilePreviewUIState = ProfilePreviewUIState.Loading,
        nostrRepository = recorder,
        relayLookupTimeout = 300.milliseconds,
    )

    @Test
    fun `initiating asks both sets of relays once, and waits`() = runBlocking {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { recorder.effects.size == 2 }

        assertEquals(bothAsks, recorder.effects)
        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)

        // A recomposition calling it again opens no second collect and asks nothing more.
        viewModel.initiate()
        delay(50)
        assertEquals(bothAsks, recorder.effects)
    }

    @Test
    fun `a placeholder row is not a person`() = runBlocking {
        val recorder = Recorder(profile = MutableStateFlow(placeholder(alice)))
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { recorder.effects.size == 2 }
        delay(50)

        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)
    }

    /** Cached first: a person already known is on screen before any relay answers. */
    @Test
    fun `a row already resolved loads at once, and the relays are still asked`() = runBlocking {
        val recorder = Recorder(profile = MutableStateFlow(resolved(alice)))
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState is ProfilePreviewUIState.Loaded }

        assertEquals(ProfilePreviewUIState.Loaded(resolved(alice)), viewModel.profilePreviewUIState)
        await { recorder.effects.size == 2 }
        assertEquals(bothAsks, recorder.effects)
    }

    @Test
    fun `a kind 0 arriving from the relays loads`() = runBlocking<Unit> {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { recorder.effects.size == 2 }
        recorder.profile.value = placeholder(alice)
        recorder.profile.value = resolved(alice)

        await { viewModel.profilePreviewUIState is ProfilePreviewUIState.Loaded }
        assertEquals(ProfilePreviewUIState.Loaded(resolved(alice)), viewModel.profilePreviewUIState)

        // Loaded is loaded: the timeout, had it still been armed, must not take it back.
        delay(400)
        assertIs<ProfilePreviewUIState.Loaded>(viewModel.profilePreviewUIState)
    }

    @Test
    fun `nothing arriving is not found, and a late kind 0 still loads`() = runBlocking {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.NotFound }

        recorder.profile.value = resolved(alice)
        await { viewModel.profilePreviewUIState is ProfilePreviewUIState.Loaded }
    }

    @Test
    fun `try again from not found asks both sets of relays again`() = runBlocking {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.NotFound }

        viewModel.retry()
        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)
        await { recorder.effects.size == 4 }
        assertEquals(bothAsks + bothAsks, recorder.effects)

        // And the clock was restarted with it.
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.NotFound }
    }

    @Test
    fun `a queue that refuses is an error, and try again asks again`() = runBlocking {
        val recorder = Recorder(refuse = true)
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.Error }

        // The timeout was cancelled with it: an error does not later become "not found".
        delay(400)
        assertEquals(ProfilePreviewUIState.Error, viewModel.profilePreviewUIState)

        viewModel.retry()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.Error }
    }

    // --- fixtures -----------------------------------------------------------------------

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    /** What every mint site writes: the "LOADING..." name, stamped `GENESIS_AT`. */
    private fun placeholder(publicKey: HexKey) = Profile(
        publicKey = publicKey,
        displayName = "LOADING...",
        nostrEventId = "placeholder",
        createdAt = GENESIS_AT,
    )

    private fun resolved(publicKey: HexKey) = Profile(
        publicKey = publicKey,
        displayName = "Alice",
        nostrEventId = "kind0",
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
        updatedAt = Instant.fromEpochSeconds(1_700_000_000),
        savedAt = Instant.fromEpochSeconds(1_700_000_000),
    )
}
