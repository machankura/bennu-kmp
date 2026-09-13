package to.curare.compose.ui.view.state

import to.curare.compose.database.model.Profile

/**
 * The person behind a pasted npub, before a chat with them is started.
 *
 * `Loaded` needs a profile read off a kind 0 -- `Profile.isResolved()` -- and never
 * the "LOADING..." placeholder a pubkey gets on first sight, because the screen's one
 * job is to show who this is. See docs/npub-profile-preview.md.
 */
sealed interface ProfilePreviewUIState {
    data object Loading : ProfilePreviewUIState

    data class Loaded(
        val profile: Profile,
    ) : ProfilePreviewUIState

    /**
     * Every relay asked has had its time, and no kind 0 came. Not an error: a search
     * that found nothing, and one a late answer can still overturn.
     */
    data object NotFound : ProfilePreviewUIState

    /** The queue itself refused: nothing was asked, and asking again is the retry. */
    data object Error : ProfilePreviewUIState
}
