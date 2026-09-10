package to.curare.compose.ui.view.state

abstract class NavigationUIState {
    data object Error: NavigationUIState()

    data class Loading(
        val text: String? = null
    ): NavigationUIState()

    data object StartupPhoenix: NavigationUIState()
    data object Landing: NavigationUIState()

    data class UnsignedProfile(
        val unsignedNostrEvent: to.curare.compose.database.model.UnsignedNostrEvent,
    ) : NavigationUIState()

    data class UnsyncedProfile(
        val unsignedNostrEvent: to.curare.compose.database.model.UnsignedNostrEvent,
    ) : NavigationUIState()

    data class UnqueuedProfileSynchronization(
        val unsignedNostrEvent: to.curare.compose.database.model.UnsignedNostrEvent,
    ) : NavigationUIState()

    data class UnindexedProfile(
        val nostrEvent: to.curare.compose.database.model.NostrEvent,
    ) : NavigationUIState()

    data class UnqueuedProfile(
        val profile: to.curare.compose.database.model.Profile,
    ) : NavigationUIState()


    data class UnannouncedProfile(
        val broadcastNostrEventRequest: to.curare.compose.database.model.BroadcastNostrEventRequest,
    ) : NavigationUIState()



    data class ProfileLoaded(
        val publicKey: String
    ) : NavigationUIState()
}