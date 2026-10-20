package com.machankura.bennu.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.database.model.intermdiate.LocalParticipant

sealed interface SelectSubgroupAdminsUIState {
    data class Loaded(
        val parentRoom: LocalChatRoom,
        /**
         * Who can be picked: the parent's own members, admins and non-admins
         * alike.
         *
         * Not filtered to the parent's admins on purpose. The point of a subgroup
         * is that it can be run by people the parent does not let run the parent,
         * so a picker that only offered existing admins would offer the one thing
         * subgroups are not for.
         */
        val candidates: List<LocalParticipant> = emptyList(),
        /**
         * Members with no unconsumed key package, who therefore cannot be
         * welcomed into the room at step 4.
         *
         * Checked here rather than at creation because creation is three
         * ceremonies later: a ChillDKG, a parent quorum and a child quorum all
         * have to complete before `MarmotGroupCreation` would discover it, and
         * each of those needed every selected admin to show up and approve.
         */
        val withoutKeyPackage: Set<HexKey> = emptySet(),
        /** Null while the lookup is still out; a set once it has settled. */
        val isResolvingKeyPackages: Boolean = true,
    ): SelectSubgroupAdminsUIState

    data class Error(val message: String): SelectSubgroupAdminsUIState

    data object Loading: SelectSubgroupAdminsUIState
}
