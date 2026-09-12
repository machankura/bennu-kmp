package to.curare.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.view.state.GroupNostrProfileUIState

/**
 * The group's nostr profile, read once for the screen that shows it.
 *
 * One read rather than a watch, the way the group's screen reads it: a profile
 * changes when a quorum signs a new one, which lands as a group event this
 * device processes, and the screen is re-entered -- and re-read -- on the way
 * back from the signing session that made it.
 */
class GroupNostrProfileViewModel(
    val chatRoomId: String,
    initialGroupNostrProfileUIState: GroupNostrProfileUIState,
    val chatRepository: ChatRepository,
): ViewModel() {

    var groupNostrProfileUIState: GroupNostrProfileUIState by mutableStateOf(
        initialGroupNostrProfileUIState
    )
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupNostrProfileUIState = if (localChatRoom == null) {
                GroupNostrProfileUIState.Error("Couldn't find the group")
            } else {
                GroupNostrProfileUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupNostrProfile = chatRepository.groupNostrProfile(chatRoomId),
                    // A NIP-17 room is excluded by the first half: it has no key
                    // of its own to sign as, so nothing to hold a share of.
                    canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                        chatRepository.canSign(chatRoomId),
                )
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialGroupNostrProfileUIState: GroupNostrProfileUIState =
                GroupNostrProfileUIState.Loading,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupNostrProfileViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupNostrProfileUIState = initialGroupNostrProfileUIState,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
