package com.machankura.bennu.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The shared-key ceremony for one NIP-17 group.
 *
 * Deliberately says nothing about *what the ceremony is for*. A subgroup's
 * ceremony grows the ladder by a rung -- the parent's birth certificate -- and
 * that is read off `DkgSession.parentChatRoomId`, never off the route. Only the
 * member who opened a subgroup could ever have put it here, and every other way
 * into this screen is from a room; a route that knows more than the room does is
 * a route that is right for one caller and silently wrong for the rest.
 */
@Serializable
data class DkgRitualRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    /**
     * Which of the room's ceremonies to show, when the caller knows.
     *
     * A room can hold more than one -- a subgroup whose admins are the whole
     * group runs in the room the group's own ceremony ran in -- so "the room's
     * ceremony" does not name one thing. A transcript line knows which it is
     * about and passes it; a caller with only a room passes null and gets the
     * newest, which is the best answer available to it.
     */
    val dkgSessionId: String? = null
): Route()
