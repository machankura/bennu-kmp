package to.curare.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * First step of making a subgroup: who runs it, what it is called, and on what
 * quorum.
 *
 * All three have to be settled before anything is published. ChillDKG hashes the
 * threshold and the host keys into the session identity, so `t` is fixed the
 * moment the proposal goes out -- there is no later screen where it could be
 * changed, and a group that disagrees about it gets no key at all rather than a
 * weak one.
 */
@Serializable
data class SelectSubgroupAdminsRoute(
    val activeUserPublicKey: String,
    val parentChatRoomId: String,
    val relayHint: String? = null
): Route()
