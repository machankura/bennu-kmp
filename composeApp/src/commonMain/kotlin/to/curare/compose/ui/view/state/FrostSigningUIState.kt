package to.curare.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.FrostSigningItem
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.Participant
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalParticipant

sealed interface FrostSigningUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,

        /** Null on the first emission, before the session has been collected. */
        val session: FrostSigningSession? = null,

        /**
         * The events the session signs, in the order its proposal fixed. Empty
         * on the first emission, and for a session whose proposal has not landed.
         */
        val items: List<FrostSigningItem> = emptyList(),

        /** Who has offered a nonce, so the screen can name who it is waiting on. */
        val offeredNonce: Set<HexKey> = emptySet(),

        /** Who has signed their part. */
        val signed: Set<HexKey> = emptySet(),

        /**
         * Who has refused. Named for the same reason the other two are: a
         * t-of-n session survives some number of refusals and not one more, so
         * which members those are is the difference between a session that is
         * one refusal from ending and one that is nowhere near.
         */
        val declined: Set<HexKey> = emptySet(),

        /**
         * Who holds a share of the key the session signs with -- the ceremony's
         * participants, read off the ceremony rather than the room. Empty until
         * the session has been collected, and so until [members] has anyone in it.
         */
        val shareHolders: Set<HexKey> = emptySet(),
    ): FrostSigningUIState {
        /**
         * The members the session can be answered by, as rows to draw.
         *
         * The share holders and nobody else. The room's roster is the wrong
         * list twice over: a room can hold members added after its key was
         * made, who hold no share and cannot sign or refuse, and a subgroup's
         * ceremony room is the parent, which holds everybody the subgroup is
         * not. The list is whose door to knock on, and a door that cannot
         * answer does not belong on it.
         *
         * Profiles still come off the room, since that is where the rows with
         * names in them are, in the order the room keeps them. A share holder
         * the room no longer holds -- one who left after the ceremony -- still
         * has a share and a place in the count, so they get a placeholder row
         * rather than being dropped.
         */
        val members: List<LocalParticipant> get() {
            val roomMembers = localChatRoom.localParticipants
                .distinctBy { it.participant.participantPublicKey }
                .associateBy { it.participant.participantPublicKey }

            val inRoom = roomMembers.values.filter { it.participant.participantPublicKey in shareHolders }
            val elsewhere = (shareHolders - roomMembers.keys).sorted().map { publicKey ->
                LocalParticipant(
                    Participant(
                        participantPublicKey = publicKey,
                        chatRoomId = localChatRoom.chatRoom.id,
                        relayHint = null
                    ),
                    null
                )
            }

            return inRoom + elsewhere
        }
    }

    data class Error(
        val message: String
    ): FrostSigningUIState

    data object Loading: FrostSigningUIState
}
