package com.it.curated.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.nostr.GroupRelay
import com.it.curated.compose.nostr.GroupRelayList
import com.it.curated.compose.nostr.GroupRelaySet
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.FrostSigningRepository
import com.it.curated.compose.ui.view.state.EditGroupRelaysUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * The group's relay lists, edited locally and proposed together.
 *
 * Nothing here is saved. The working copy lives in [working] until the button is
 * pressed, and what the button does is open one signing session over the sets that
 * changed -- so the group's relay lists change on every member's device at once,
 * when a quorum has signed, or not at all.
 *
 * ### Why one session rather than one per set
 *
 * Wisp publishes a tab at a time because a person's client signs for itself and
 * there is nothing to coordinate. Here every signature costs a quorum's attention,
 * and four separate sessions for one sitting at one screen would ask for it four
 * times over what is plainly a single decision: where this group lives.
 *
 * The batch carries **only the sets that differ**, which is what keeps that honest.
 * Re-signing a list nobody touched would put a second, identical statement on the
 * record with a newer timestamp -- harmless, since these are replaceable, and still
 * a lie about when the group last decided anything.
 *
 * `FrostSigningManager` documents the cost of batching: a batch is all-or-nothing
 * and so only as available as its worst item. These four items are the same size,
 * the same shape and signed by the same quorum in the same second, so there is no
 * worst one to be dragged down by.
 */
class EditGroupRelaysViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    initialEditGroupRelaysUIState: EditGroupRelaysUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var editGroupRelaysUIState: EditGroupRelaysUIState by mutableStateOf(
        initialEditGroupRelaysUIState
    )
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The working copy: what the lists would be if the group agreed.
     *
     * A snapshot map so the editor recomposes as rows are added and removed, and
     * held on the view model rather than in the composition so that switching tabs
     * -- which is a pager page changing -- does not throw away an edit.
     */
    val working = mutableStateMapOf<GroupRelaySet, List<GroupRelay>>()

    /** Which set the editor is showing, and so what the add field adds to. */
    var selectedSet: GroupRelaySet by mutableStateOf(GroupRelaySet.entries.first())
        private set

    /**
     * Why the last add did not happen, or null. Cleared by the next keystroke.
     *
     * Named rather than silent: Wisp's add button does nothing at all for a URL it
     * refuses, and "nothing happened" is indistinguishable from a missed tap.
     */
    var addFailure: AddFailure? by mutableStateOf(null)
        private set

    enum class AddFailure {
        /** Not a `wss://` URL, or one pointing at this machine. */
        NotARelay,

        /** Already in this set, which is a no-op rather than a mistake. */
        AlreadyListed,
    }

    fun initiateEditGroupRelays() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            editGroupRelaysUIState = if (localChatRoom == null) {
                EditGroupRelaysUIState.Error("Couldn't find the chat room")
            } else {
                EditGroupRelaysUIState.Loaded(
                    localChatRoom = localChatRoom,
                    signed = chatRepository.groupRelayLists(chatRoomId),
                    canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                        frostSigningRepository.canSign(chatRoomId),
                )
            }
        }
    }

    /**
     * Fills the working copy from what the group signed, leaving edits alone.
     *
     * A set the group has agreed starts at its list; one it has not starts at the
     * set's defaults, which is what puts this build's own relay in front of a group
     * setting up for the first time rather than an empty list. See
     * `GroupRelaySet.defaults`.
     *
     * Called from the screen rather than from [initiateEditGroupRelays], because
     * the loader is not the only way a loaded state arrives -- a preview and a
     * layout test both hand one straight in, and seeding only on the load path gave
     * both of them an editor with nothing in it while the app worked fine. The
     * screen has the state either way.
     *
     * **Only fills what is missing**, so running again after the member has typed
     * something does not undo it. That matters because the effect that calls this
     * is keyed on a state the view model can re-emit.
     */
    fun seedWorkingCopy(signed: List<GroupRelayList>) {
        signed.forEach { list ->
            if (list.set in working) return@forEach

            working[list.set] = if (list.isAgreed) list.relays else list.set.defaults()
        }
    }

    fun selectSet(set: GroupRelaySet) {
        selectedSet = set
        addFailure = null
    }

    fun clearAddFailure() {
        addFailure = null
    }

    fun relaysOf(set: GroupRelaySet): List<GroupRelay> = working[set] ?: emptyList()

    /**
     * Adds [url] to the selected set, or says why it did not.
     *
     * True when the row appeared, which is the caller's cue to clear the field.
     */
    fun addRelay(url: String): Boolean {
        val relayUrl = GroupRelaySet.relayUrlOrNull(url) ?: run {
            addFailure = AddFailure.NotARelay
            return false
        }

        val current = relaysOf(selectedSet)
        if (current.any { it.url == relayUrl }) {
            addFailure = AddFailure.AlreadyListed
            return false
        }

        working[selectedSet] = current + GroupRelay(relayUrl)
        addFailure = null
        return true
    }

    fun removeRelay(set: GroupRelaySet, relay: GroupRelay) {
        working[set] = relaysOf(set).filterNot { it.url == relay.url }
    }

    /**
     * Turns a NIP-65 marker on or off, refusing to turn off the last one.
     *
     * A relay that is neither read nor write has no tag in NIP-65 -- see
     * `GroupRelaySet.template` -- so the state does not exist to be put the group's
     * signature on. What it would mean is that the relay is not in the list, and
     * removing it is the row's own button.
     */
    fun toggleRead(set: GroupRelaySet, relay: GroupRelay) =
        toggle(set, relay) { it.copy(read = !it.read) }

    fun toggleWrite(set: GroupRelaySet, relay: GroupRelay) =
        toggle(set, relay) { it.copy(write = !it.write) }

    private fun toggle(
        set: GroupRelaySet,
        relay: GroupRelay,
        change: (GroupRelay) -> GroupRelay,
    ) {
        working[set] = relaysOf(set).map {
            if (it.url != relay.url) return@map it

            change(it).takeIf { changed -> changed.read || changed.write } ?: it
        }
    }

    /**
     * The sets whose working copy says something different to what the group
     * signed, in the order the editor shows them.
     *
     * Order-sensitive on purpose. A relay list is written in the order it is read
     * back, and a member who moved a relay to the front meant to; comparing as sets
     * would call that no change and quietly refuse to propose it.
     *
     * A set the group has never agreed counts as changed as soon as it holds
     * anything, so a first-time group's seeded defaults are a real proposal rather
     * than an accident, and an empty one it has never agreed counts as unchanged --
     * there is nothing to say.
     */
    fun changedSets(signed: List<GroupRelayList>): List<GroupRelaySet> =
        signed.filter { list -> relaysOf(list.set) != list.relays }
            .filterNot { list -> !list.isAgreed && relaysOf(list.set).isEmpty() }
            .map { it.set }

    /**
     * Opens one session over every set that changed.
     *
     * [onNothingToPropose] rather than a session over nothing:
     * `proposeSigningBatch` refuses an empty batch outright, and a member who has
     * changed nothing is asking a question rather than making a mistake.
     */
    fun proposeRelayLists(
        localChatRoom: LocalChatRoom,
        signed: List<GroupRelayList>,
        onSuccess: (sessionId: String) -> Unit,
        onNothingToPropose: () -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits. Two sessions over two versions of one relay
        // list would leave the group's answer decided by whichever quorum finished
        // last.
        if (isActionPending.value) return

        val changed = changedSets(signed)
        if (changed.isEmpty()) {
            onNothingToPropose.invoke()
            return
        }

        isActionPending.value = true

        val templates = changed.map { set -> set.template(relaysOf(set)) }

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigningBatch(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    events = templates,
                )
            }.onFailure { error ->
                logger.e("Failed to propose relay lists for $chatRoomId", error)
            }.getOrNull()

            viewModelScope.launch(Dispatchers.Main) {
                if (session != null) {
                    onSuccess.invoke(session.id)
                } else {
                    onFailure.invoke()
                }
            }

            isActionPending.value = false
        }
    }

    companion object {
        private const val TAG = "EditGroupRelaysViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            initialEditGroupRelaysUIState: EditGroupRelaysUIState =
                EditGroupRelaysUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditGroupRelaysViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    initialEditGroupRelaysUIState = initialEditGroupRelaysUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
