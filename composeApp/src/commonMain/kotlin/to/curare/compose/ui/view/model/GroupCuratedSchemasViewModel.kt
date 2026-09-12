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
import to.curare.compose.ui.view.state.GroupCuratedSchemasUIState

/**
 * The schemas of the lists the group curates, read once for the screen that
 * shows them. One read rather than a watch, for the reason
 * `GroupNostrProfileViewModel` gives.
 */
class GroupCuratedSchemasViewModel(
    val chatRoomId: String,
    initialGroupCuratedSchemasUIState: GroupCuratedSchemasUIState,
    val chatRepository: ChatRepository,
): ViewModel() {

    var groupCuratedSchemasUIState: GroupCuratedSchemasUIState by mutableStateOf(
        initialGroupCuratedSchemasUIState
    )
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupCuratedSchemasUIState = if (localChatRoom == null) {
                GroupCuratedSchemasUIState.Error("Couldn't find the group")
            } else {
                GroupCuratedSchemasUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupCuratedSchemas = chatRepository.groupCuratedSchemas(chatRoomId),
                    canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                        chatRepository.canSign(chatRoomId),
                )
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialGroupCuratedSchemasUIState: GroupCuratedSchemasUIState =
                GroupCuratedSchemasUIState.Loading,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupCuratedSchemasViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupCuratedSchemasUIState = initialGroupCuratedSchemasUIState,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
