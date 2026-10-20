package com.machankura.bennu.compose.ui.view.model

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
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.ui.view.state.GroupRelaysUIState

/**
 * The group's relay lists, read once for the screen that shows them. One read
 * rather than a watch, for the reason `GroupNostrProfileViewModel` gives.
 */
class GroupRelaysViewModel(
    val chatRoomId: String,
    initialGroupRelaysUIState: GroupRelaysUIState,
    val chatRepository: ChatRepository,
): ViewModel() {

    var groupRelaysUIState: GroupRelaysUIState by mutableStateOf(initialGroupRelaysUIState)
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupRelaysUIState = if (localChatRoom == null) {
                GroupRelaysUIState.Error("Couldn't find the group")
            } else {
                GroupRelaysUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupRelayLists = chatRepository.groupRelayLists(chatRoomId),
                    canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                        chatRepository.canSign(chatRoomId),
                )
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialGroupRelaysUIState: GroupRelaysUIState = GroupRelaysUIState.Loading,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupRelaysViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupRelaysUIState = initialGroupRelaysUIState,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
