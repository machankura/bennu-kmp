package to.curare.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.nostr.nip30303.DialectEvent
import to.curare.compose.ui.view.state.AddDialectUIState

class AddDialectViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialAddDialectUIState: AddDialectUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var addDialectUIState: AddDialectUIState by mutableStateOf(initialAddDialectUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * Whether this group holds a key it can sign with.
     *
     * Read before the form is offered: a group without one cannot make a dialect
     * this way at all, and a button that always fails is worse than no button.
     */
    suspend fun canSign(): Boolean = frostSigningRepository.canSign(chatRoomId)

    fun initiateAddDialect() {
        logger.d("compressed (most likely chat room): $chatRoomId")
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            addDialectUIState = if (localChatRoom == null) {
                AddDialectUIState.Error("Couldn't find the chat room")
            } else {
                AddDialectUIState.Loaded(
                    localChatRoom = localChatRoom,
                    canSign = frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Asks the group to sign a new dialect into existence.
     *
     * The dialect is not created here and does not exist yet. What goes out is a
     * proposal to sign it, and the dialect appears -- on every member's device at
     * once, authored by this room's own key rather than by whoever typed it --
     * when enough members have signed. That author is the room's id: signing runs
     * at the path the room was derived at, so a dialect says which room made it
     * simply by being signed.
     *
     * That is the difference from submitting one. A submission says "I am putting
     * this in front of the group" and the group's only recourse afterwards is
     * social. A signature is the group saying it, and it takes a quorum to say.
     */
    fun addDialect(
        localChatRoom: LocalChatRoom,
        nameField: TextFieldState,
        countryField: TextFieldState,
        languageField: TextFieldState,
        onSuccess: (sessionId: String) -> Unit,
        onFailure: () -> Unit
    ) {
        val name = nameField.text.toString()
        val country = countryField.text.toString()
        val language = languageField.text.toString()

        if (name.isBlank() || country.isBlank() || language.isBlank()) {
            onFailure.invoke()
            return
        }

        // Guard against double submits from repeated FAB taps.
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val dialectEventTemplate = DialectEvent.build(
                name = name,
                country = country,
                language = language,
            )

            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = dialectEventTemplate.kind,
                    tags = dialectEventTemplate.tags,
                    content = dialectEventTemplate.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a dialect for signing", error)
            }.getOrNull()

            if (session != null) {
                nameField.clearText()
                countryField.clearText()
                languageField.clearText()

                viewModelScope.launch(Dispatchers.Main) {
                    onSuccess.invoke(session.id)
                }
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    onFailure.invoke()
                }
            }

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "AddDialectViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialAddDialectUIState: AddDialectUIState = AddDialectUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AddDialectViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialAddDialectUIState = initialAddDialectUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
