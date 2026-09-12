package to.curare.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import to.curare.compose.database.model.intermdiate.LocalAccount
import to.curare.compose.ui.composable.navigation.routes.SovereignWalletStartupRoute
import to.curare.compose.ui.view.state.NavigationUIState
import co.touchlab.kermit.Logger
import to.curare.compose.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class NavigationViewModel(
    val activeIdentityStateFlow: StateFlow<Identity?>,
    initialNavigationUIState: NavigationUIState,
    val nostrRepository: to.curare.compose.repository.NostrRepository,
    val scope: CoroutineScope,
): ViewModel() {

    companion object {
        private const val TAG = "NavigationViewModel"

        /** Broadcast statuses that mean "still on its way to a relay". */
        private val PENDING_BROADCAST_STATUSES = setOf("pending", "processing")

        fun factory(
            activeIdentity: StateFlow<Identity?>,
            initialNavigationUIState: NavigationUIState,
            nostrRepository: to.curare.compose.repository.NostrRepository,
            scope: CoroutineScope,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NavigationViewModel(
                    activeIdentity,
                    initialNavigationUIState = initialNavigationUIState,
                    nostrRepository = nostrRepository,
                    scope = scope
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    private val _navigationUIState = MutableStateFlow(
        initialNavigationUIState
    )
    val navigationUIState = _navigationUIState.asStateFlow()

    init {
        observeProfile()
    }

    suspend fun loadNostrProfile(startupRoute: SovereignWalletStartupRoute? = null) {
        val activeUserPublicKey = nostrRepository.getLocalAccounts().firstOrNull()?.profile?.publicKey

        if (activeUserPublicKey == null) {
            // TODO: We can just go directly to startup witout fear...
            _navigationUIState.getAndUpdate {
                if (startupRoute != null) {
                    NavigationUIState.Landing
                } else {
                    NavigationUIState.StartupPhoenix
                }
            }
        } else if (startupRoute != null) {
            // This branch was commented out when `observeProfile` was renamed to
            // `observeLocalAccount`, which turned the whole call into a no-op for anyone who
            // already had an account: the caller had navigated to a loading screen first and then
            // nothing ever moved the user off it. Every path out of here must leave the navigation
            // state pointing somewhere.
            processLocalAccount(
                nostrRepository.observeLocalAccount(
                    publicKey = activeUserPublicKey
                ).firstOrNull()
            )
        } else {
            _navigationUIState.getAndUpdate {
                NavigationUIState.StartupPhoenix
            }
        }
    }


    private fun processLocalAccount(localAccount: LocalAccount?) {
        logger.i("processLocalAccount: $localAccount")

        _navigationUIState.getAndUpdate {
            if (localAccount == null) {
                logger.i("We need to be on the landing screen so user creates account")
                NavigationUIState.Landing
            } else if (localAccount.unsignedNostrEvent == null) {
                logger.i("We need to be on the landing screen so user creates account")
                NavigationUIState.Landing
            } else if (localAccount.broadcastNostrEventReceipt != null) {
                logger.i("We have a broadcast receipt: ${localAccount.profile}")
                NavigationUIState.ProfileLoaded(
                    publicKey = localAccount.unsignedNostrEvent.pubKey
                )
            } else if (localAccount.broadcastNostrEventRequest != null &&
                localAccount.broadcastNostrEventRequest.status in PENDING_BROADCAST_STATUSES
            ) {
                // Must be checked BEFORE the signed-and-indexed branch below. `publishNostrEvent`
                // stamps `signedAt` and upserts the local Profile in the same transaction, so
                // that branch went true the instant the kind-0 was signed and this gate — the one
                // that exists to hold the user until the profile is actually on a relay — was
                // unreachable. A profile that lived only on the device looked fully announced.
                //
                // Deliberately scoped to in-flight statuses: once a broadcast has been marked
                // "failed" we fall through rather than park the user on a screen with no way out.
                logger.i("We have a broadcast request: ${localAccount.broadcastNostrEventRequest}")
                NavigationUIState.UnannouncedProfile(
                    broadcastNostrEventRequest = localAccount.broadcastNostrEventRequest
                )
            } else if (localAccount.unsignedNostrEvent.signedAt != null && localAccount.profile != null) {
                logger.i("We have successfully synced a profile: ${localAccount.profile}")
                NavigationUIState.ProfileLoaded(
                    publicKey = localAccount.unsignedNostrEvent.pubKey
                )
            }  else if (localAccount.profile != null) {
                logger.i("We have a profile that needs to queued for broadcast: ${localAccount.profile}")
                NavigationUIState.UnqueuedProfile(
                    profile = localAccount.profile
                )
            } else if (localAccount.nostrEvent != null) {
                logger.i("Nostr event with the profile needs to be indexed: ${localAccount.nostrEvent}")
                NavigationUIState.UnindexedProfile(
                    nostrEvent = localAccount.nostrEvent
                )
            } else if (localAccount.unsignedNostrEvent.signedAt != null && localAccount.synchronizeNostrEventRequests.isEmpty()) {
                logger.i("We should request to sync profile: ${localAccount.unsignedNostrEvent}")
                NavigationUIState.UnqueuedProfileSynchronization(
                    unsignedNostrEvent = localAccount.unsignedNostrEvent
                )
            // `isNotEmpty`: this branch is the "a sync is already queued" case. It used to repeat
            // the predicate above verbatim, which made it dead code.
            }  else if (localAccount.unsignedNostrEvent.signedAt != null && localAccount.synchronizeNostrEventRequests.isNotEmpty()) {
                logger.i("We should be syncing the profile: ${localAccount.unsignedNostrEvent}")
                NavigationUIState.UnsyncedProfile(
                    unsignedNostrEvent = localAccount.unsignedNostrEvent
                )
            } else {
                logger.i("We have an unsigned profile: ${localAccount.unsignedNostrEvent}")
                NavigationUIState.UnsignedProfile(
                    unsignedNostrEvent = localAccount.unsignedNostrEvent
                )
            }
        }
    }


    private suspend fun loadNostrProfile(activeUserPublicKey: String) {
        nostrRepository.observeLocalAccount(
            publicKey = activeUserPublicKey
        ).distinctUntilChanged().collect { localProfile ->
            processLocalAccount(localProfile)
        }
    }

    private fun observeProfile() {
        logger.i("observeProfile")
        scope.launch(Dispatchers.IO) {
            delay(2_100.milliseconds) // Looking busy...

            activeIdentityStateFlow.collectLatest { identity ->
                logger.d("Active identity: $identity")
                if (identity == null) {
                    // Nothing has been started: go and start something.
                    _navigationUIState.getAndUpdate {
                        NavigationUIState.StartupPhoenix
                    }
                } else {
                    // The identity carries its own key, so there is no node to wait on and no
                    // "couldn't get the nostrKey" dead end. The branch that used to send a
                    // nodeless identity back to startup went with it: an identity without a
                    // node is now a kind of identity, not a wallet that has not finished
                    // starting.
                    logger.i("Observing: ${identity.nostrPublicKey}")

                    loadNostrProfile(identity.nostrPublicKey)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        logger.i("AppViewModel cleared")
    }

}