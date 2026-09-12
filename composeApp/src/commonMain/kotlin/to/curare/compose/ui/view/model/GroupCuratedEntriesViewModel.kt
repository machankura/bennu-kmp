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
import to.curare.compose.ui.view.state.GroupCuratedEntriesUIState

/**
 * The entries the group has accepted into its lists, read once for the screen
 * that shows them. One read rather than a watch, for the reason
 * `GroupNostrProfileViewModel` gives. Nothing here asks whether this device
 * can sign, because nothing on the screen would change if it could.
 */
class GroupCuratedEntriesViewModel(
    val chatRoomId: String,
    initialGroupCuratedEntriesUIState: GroupCuratedEntriesUIState,
    val chatRepository: ChatRepository,
): ViewModel() {

    var groupCuratedEntriesUIState: GroupCuratedEntriesUIState by mutableStateOf(
        initialGroupCuratedEntriesUIState
    )
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupCuratedEntriesUIState = if (localChatRoom == null) {
                GroupCuratedEntriesUIState.Error("Couldn't find the group")
            } else {
                GroupCuratedEntriesUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupCuratedEntries = chatRepository.groupCuratedEntries(chatRoomId),
                )
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialGroupCuratedEntriesUIState: GroupCuratedEntriesUIState =
                GroupCuratedEntriesUIState.Loading,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupCuratedEntriesViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupCuratedEntriesUIState = initialGroupCuratedEntriesUIState,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
