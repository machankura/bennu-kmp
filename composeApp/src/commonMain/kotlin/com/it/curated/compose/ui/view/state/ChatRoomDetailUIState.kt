package com.it.curated.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.database.model.GroupKeyState
import com.it.curated.compose.managers.SubgroupManager
import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.MantraDialect
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.nostr.GroupNostrProfile
import com.it.curated.compose.nostr.GroupRelayList

sealed interface ChatRoomDetailUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val artifacts: List<MantraArtifact> = emptyList(),
        val dialects: List<MantraDialect> = emptyList(),
        /**
         * What the group has said this room signs with, or null while it has
         * said nothing.
         *
         * Sits above everything else on the screen because it is the fact the
         * rest of the room's signed work stands on: which key the room signs as
         * decides whether anything it has signed will be believed.
         */
        val groupKeyState: GroupKeyState? = null,
        /**
         * The event the group signed to say it, as the group signed it.
         *
         * Held beside [groupKeyState] rather than derived from it, because a
         * state is this device's reading and this is the statement -- with the
         * signature on it, which is the part worth copying somewhere it can be
         * checked. Null on a device that holds the reading and not the event.
         */
        val signedGroupKeyStateEvent: GroupSignedEvent? = null,
        /**
         * The groups this one has certified as its children.
         *
         * Off the parent's own signed certificates, so it lists a subgroup this
         * device holds no room for -- which is the normal position of a member
         * who is not in it, and of everybody between the certificate being
         * signed and the room being created.
         */
        val subgroups: List<SubgroupManager.Subgroup> = emptyList(),
        /**
         * The group this room is a subgroup of, or null when it is nobody's
         * child.
         *
         * Verified: it comes off the room's key state, which only carries a
         * parent whose certificate was checked. A member welcomed in after the
         * founding holds no state and so sees nothing here, even in a room that
         * really is a subgroup.
         */
        val parentChatRoomId: HexKey? = null,
        /**
         * The kind:0 the group signed about itself, or null while it has signed
         * none.
         *
         * Sits under the room's identity and above its work, because that is what
         * it is: the name and the picture the rest of nostr sees on the key the
         * signing key row names. Null is an ordinary state and the screen says so
         * -- a group has an identity from the moment it has a key and describes it
         * whenever somebody gets round to it.
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Where the group has said it lives on nostr, one entry per relay set.
         *
         * Beside the profile because they are the same kind of fact -- the group's
         * own account of itself, signed by the group -- and read in the same breath
         * by anybody checking whether the group is reachable.
         *
         * Empty only in a room that has none of this to say. Every other case has
         * four entries, some of them unsigned; see `ChatRepository.groupRelayLists`.
         */
        val groupRelayLists: List<GroupRelayList> = emptyList(),
        /**
         * Whether this device could sign a profile for the group.
         *
         * The same capability [canAddSubgroup] wants, and wanted for the same
         * reason: writing the group's profile is a signature by the group, and
         * `FrostSigningManager.proposeSigningBatch` throws for a device holding no
         * share of the key. It is not about permission -- any member with a share
         * may propose -- and the quorum is what decides.
         *
         * A NIP-17 room is excluded by the same condition, since it has no key of
         * its own to sign as and so no nostr identity to describe.
         */
        val canEditNostrProfile: Boolean = false,
        /**
         * Whether this device could open a subgroup here at all.
         *
         * Two conditions and both are about capability rather than permission.
         * The room has to be a Marmot one, since a NIP-17 room has no admins to
         * be a parent's. And this device has to hold a share of the group's key,
         * because certifying a child is a signature by the parent and
         * `FrostSigningManager.proposeSigningBatch` throws for a device that
         * cannot make one -- throwing at a button is not a UI.
         *
         * The rest of the refusals are the picker's, where they can name who is
         * missing and what to do about it.
         */
        val canAddSubgroup: Boolean = false,
    ): ChatRoomDetailUIState

    data class Error(
        val message: String
    ): ChatRoomDetailUIState
    data object Loading: ChatRoomDetailUIState
}

