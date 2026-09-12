package to.curare.compose.ui.view.state

import to.curare.compose.identity.CredentialProblem
import to.curare.compose.identity.SignInCredential

/**
 * The sign-in screen's four states. Problems with the *input* are not one of them: those
 * stay on the field as supporting text while the prompt is still showing. [Error] is for
 * what happens after the user has confirmed -- the write failed, or the key was already
 * here -- where there is no field to put the message under.
 */
sealed interface SignInToProfileUIState {
    /** The field, waiting for a recovery phrase or an nsec. */
    data object InputPrompt : SignInToProfileUIState

    /**
     * What was recognised, and the npub it signs as, before anything is written. This is
     * the step that catches a paste of the wrong key; for a recovery phrase it is where
     * the user learns which profile the words open. The secret itself is never shown.
     */
    data class Confirm(val credential: SignInCredential) : SignInToProfileUIState

    /** Writing the secret and planting the account. */
    data object Committing : SignInToProfileUIState

    data class Error(val problem: CredentialProblem) : SignInToProfileUIState
}
