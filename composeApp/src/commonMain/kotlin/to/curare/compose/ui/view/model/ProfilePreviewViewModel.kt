package to.curare.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import to.curare.compose.nostr.DirectMessagePeerSync
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.ProfilePreviewUIState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Finds the person behind a pasted npub, and says when it has stopped looking.
 *
 * Cached first: the observed row is whatever the device already holds, so a person
 * already known is on screen before any relay answers, and the relays refresh them
 * behind it. The relays are asked twice over -- the kind 0 of the indexers, which hold
 * everyone's, and the three kinds a chat needs of the DM relays -- through
 * [DirectMessagePeerSync], so that pressing the button on the screen after this one
 * finds the key package already here.
 *
 * The timeout is the one both DM view models use, and it only changes what is shown:
 * the collect stays live past it, so a kind 0 that arrives on the twenty-first second
 * still loads. See docs/npub-profile-preview.md, Phase 1.
 */
class ProfilePreviewViewModel(
    val activeUserPublicKey: HexKey,
    val profilePublicKey: HexKey,
    initialProfilePreviewUIState: ProfilePreviewUIState,
    private val nostrRepository: NostrRepository,
    /** How long the relays get before the screen says it came up empty. A parameter so a test need not wait it out. */
    private val relayLookupTimeout: Duration = RELAY_LOOKUP_TIMEOUT,
) : ViewModel() {

    var profilePreviewUIState: ProfilePreviewUIState by mutableStateOf(initialProfilePreviewUIState)
        private set

    private val logger = Logger.withTag(TAG)

    private var started = false
    private var lookupTimeout: Job? = null

    /** Once. A recomposition that calls this again must not open a second collect. */
    fun initiate() {
        if (started) return
        started = true
        observeProfile()
        askTheRelays()
    }

    /** Asks again. The observation is still live, so the answer arrives through the same collect. */
    fun retry() {
        profilePreviewUIState = ProfilePreviewUIState.Loading
        askTheRelays()
    }

    private fun observeProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            nostrRepository.observeProfileWithPublicKey(profilePublicKey)
                // A placeholder is not a person. Mapping it to null here is what keeps
                // "LOADING..." off a screen whose one job is to say who this is.
                .map { profile -> profile?.takeIf { it.isResolved() } }
                .distinctUntilChanged()
                .collect { profile ->
                    if (profile == null) return@collect
                    lookupTimeout?.cancel()
                    profilePreviewUIState = ProfilePreviewUIState.Loaded(profile = profile)
                }
        }
    }

    private fun askTheRelays() {
        lookupTimeout?.cancel()
        lookupTimeout = viewModelScope.launch(Dispatchers.IO) {
            delay(relayLookupTimeout)
            if (profilePreviewUIState is ProfilePreviewUIState.Loading) {
                profilePreviewUIState = ProfilePreviewUIState.NotFound
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                nostrRepository.queueSynchronizeNostrEvent(
                    DirectMessagePeerSync.profileRequests(profilePublicKey)
                )
                nostrRepository.queueNegentropySynchronizeRequest(
                    DirectMessagePeerSync.requests(profilePublicKey)
                )
            } catch (e: Exception) {
                logger.e("could not ask the relays about $profilePublicKey", e)
                lookupTimeout?.cancel()
                profilePreviewUIState = ProfilePreviewUIState.Error
            }
        }
    }

    companion object {
        private const val TAG = "ProfilePreviewViewModel"

        /** How long to wait on the relays before telling the user we came up empty. */
        val RELAY_LOOKUP_TIMEOUT = 20.seconds

        fun factory(
            activeUserPublicKey: HexKey,
            profilePublicKey: HexKey,
            initialProfilePreviewUIState: ProfilePreviewUIState = ProfilePreviewUIState.Loading,
            nostrRepository: NostrRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProfilePreviewViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    profilePublicKey = profilePublicKey,
                    initialProfilePreviewUIState = initialProfilePreviewUIState,
                    nostrRepository = nostrRepository,
                )
            }
        }
    }
}
