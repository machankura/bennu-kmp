package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Everything a group has asked its shared key to sign.
 *
 * A room's own list, not the reader's: a proposal is put to the whole group, and
 * which of them are waiting on this member is a status on each row rather than a
 * different list. See [FrostSigningRoute] for one of them.
 */
@Serializable
data class ProposalListRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String
): Route()
