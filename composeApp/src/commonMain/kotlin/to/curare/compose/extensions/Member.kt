package to.curare.compose.extensions

import to.curare.compose.database.model.intermdiate.LocalParticipant
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/** Enough of a public key to tell two members apart when neither has a profile yet. */
private const val SHORTENED_PUBLIC_KEY_LENGTH = 8

/** A public key cut to something readable, for where no profile has arrived yet. */
fun HexKey.shortened(): String = take(SHORTENED_PUBLIC_KEY_LENGTH)

/**
 * What to call the member behind this public key on screen.
 *
 * Resolved from the profiles joined onto the room rather than stored anywhere, so
 * it follows a rename and is never stuck on the "LOADING..." placeholder a member
 * is given the moment they are first seen. Falls back to a short key, which is at
 * least stable and distinguishing, when there is no profile at all yet.
 */
fun HexKey.memberName(
    members: List<LocalParticipant>,
    activeUserPublicKey: HexKey
): String = if (this == activeUserPublicKey) {
    "You"
} else {
    members.firstOrNull { it.participant.participantPublicKey == this }
        ?.profile
        ?.humanReadableNameOrPubkey()
        ?: shortened()
}
