package to.curare.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import to.curare.compose.nostr.CuratedSuggestion
import to.curare.compose.nostr.curated.CuratedEntryProblem
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedSuggestionRef
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.AcceptCuratedSuggestionUIState

/**
 * One suggestion, taken into the list: the entry as somebody suggested it,
 * edited by a member, and proposed as the group's own.
 *
 * Nothing here is saved. The working copy is one input per value the entry
 * has, held on this view model rather than in the composition so that the
 * window turning does not throw an edit away, and the button folds it into a
 * kind 31890 for the room's quorum to put its key to. The list changes on
 * every member's device at once, when a quorum has signed, or not at all.
 *
 * ### What is edited, and what is kept
 *
 * Curation is editorial -- the NIP says the curator may fix a year or swap a
 * poster on the way through -- so every field the schema's form asks for is
 * open. Two things are not. The `d` is kept whatever field writes it, because
 * the canonical entry SHOULD land on the suggestion's coordinate so that
 * accepting it twice revises one entry rather than making two. And a field the
 * schema marks `derived` is kept as the suggester's client filled it in: this
 * app derives nothing, and an entry a form never asked for is not one a form
 * should let a member invent.
 *
 * ### Refused before proposed
 *
 * The button runs the queue's own verifier over the event it is about to
 * propose, and refuses with the fields named. A signature is the most expensive
 * thing this app does, and a canonical entry the queue would not count -- a
 * year outside the schema's range, an option that is not one -- would be a
 * quorum spent on an entry no client shows.
 */
class AcceptCuratedSuggestionViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    /** The `d` of the schema whose list the entry goes into. */
    val identifier: String,
    /** The event id of the suggestion being taken up. */
    val suggestionId: String,
    initialAcceptCuratedSuggestionUIState: AcceptCuratedSuggestionUIState,
    val nostrRepository: NostrRepository,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var acceptCuratedSuggestionUIState: AcceptCuratedSuggestionUIState by mutableStateOf(
        initialAcceptCuratedSuggestionUIState
    )
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The working copy: for each field the form asks for, one input per value.
     *
     * A field that repeats has as many inputs as the suggestion had values, and
     * can be given more; every other field has exactly one. Keyed by the
     * schema's field name, which is what the entry is keyed by too.
     */
    val inputs: SnapshotStateMap<String, SnapshotStateList<TextFieldState>> = mutableStateMapOf()

    /**
     * Why the last proposal did not go out, or nothing. Set by the button and
     * left showing until the next press, since the button is the thing that
     * will clear it.
     */
    var problems: List<CuratedEntryProblem> by mutableStateOf(emptyList())
        private set

    private var isSeeded = false

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                acceptCuratedSuggestionUIState = AcceptCuratedSuggestionUIState.Error("Couldn't find the group")
                return@launch
            }

            val schema = chatRepository.groupCuratedSchemas(chatRoomId).firstOrNull { it.identifier == identifier }
            if (schema == null) {
                acceptCuratedSuggestionUIState = AcceptCuratedSuggestionUIState.Error("Couldn't find that schema")
                return@launch
            }

            // The same reading the queue screen makes, so what is accepted here
            // is what was shown there: newest per coordinate, checked against
            // the schema, and marked when the group already has an entry for it.
            // An id the reading no longer holds is a version something newer has
            // replaced since the row was tapped, and the newer one is what the
            // queue now shows.
            val events = nostrRepository.observeNostrEventsMatching(schema.queueReadFilter()).first()
            val suggestion = CuratedSuggestion.queueOf(schema, events).firstOrNull { it.id == suggestionId }
            if (suggestion == null) {
                acceptCuratedSuggestionUIState = AcceptCuratedSuggestionUIState.Error("Couldn't find that suggestion")
                return@launch
            }

            acceptCuratedSuggestionUIState = AcceptCuratedSuggestionUIState.Loaded(
                localChatRoom = localChatRoom,
                schema = schema,
                suggestion = suggestion,
                canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                    frostSigningRepository.canSign(chatRoomId),
            )
        }
    }

    /**
     * Fills the working copy from the suggestion, once.
     *
     * One input per value the suggestion carries for each field the form asks
     * for, and one blank input for a field it carries nothing for, so there is
     * somewhere to type. Called from the screen rather than from [initiate],
     * because the loader is not the only way a loaded state arrives: a preview
     * and a layout test both hand one straight in. Runs once, so the effect
     * that calls it re-firing does not undo an edit.
     */
    fun seedWorkingCopy(state: AcceptCuratedSuggestionUIState.Loaded) {
        if (isSeeded) return
        isSeeded = true

        val entry = state.suggestion.entry

        editableFields(state).forEach { field ->
            val values = entry.values[field.name].orEmpty().ifEmpty { listOf("") }
            inputs[field.name] = mutableStateListOf(*values.map { TextFieldState(it) }.toTypedArray())
        }
    }

    /** Adds a blank input under [fieldName], for a field that repeats. */
    fun addValue(fieldName: String) {
        inputs[fieldName]?.add(TextFieldState())
    }

    /**
     * Drops the input at [index] under [fieldName]. The last input stays, blank
     * rather than gone, so the field is still there to be typed into.
     */
    fun removeValue(fieldName: String, index: Int) {
        val values = inputs[fieldName] ?: return
        if (index !in values.indices) return

        if (values.size == 1) {
            values[0] = TextFieldState()
        } else {
            values.removeAt(index)
        }
    }

    /**
     * The problems about [fieldName], for the field's own error state. A
     * `require-any` rule is filed under its first field, so it shows there.
     */
    fun problemsFor(fieldName: String): List<CuratedEntryProblem> = problems.filter { it.field == fieldName }

    /**
     * The problems that are about no field on the form: the entry as a whole,
     * or a field the form keeps rather than asks for. Shown at the top, since
     * there is no input to show them under.
     */
    fun problemsAboutTheEntry(state: AcceptCuratedSuggestionUIState.Loaded): List<CuratedEntryProblem> {
        val editable = editableFields(state).map { it.name }.toSet()
        return problems.filterNot { it.field in editable }
    }

    /**
     * The entry as the form has it: what was typed for every field the form
     * asks for, and what was suggested for every field it keeps.
     *
     * Keyed by field name and holding every value, which is the shape
     * `CuratedEntryEvent.canonicalTemplate` lays out as tags. Blank inputs come
     * through and are dropped there; a blank is not a value.
     */
    fun draft(state: AcceptCuratedSuggestionUIState.Loaded): Map<String, List<String>> {
        val suggested = state.suggestion.entry.values

        return state.schema.schema.fields.associate { field ->
            field.name to (inputs[field.name]?.map { it.text.toString() } ?: suggested[field.name].orEmpty())
        }
    }

    /**
     * Opens a session over the entry, or says why not.
     *
     * Two refusals before anything is proposed and each is the caller's to
     * say: an entry the queue would refuse, which is [onInvalid] with the
     * reasons left in [problems]; and a proposal that could not be opened,
     * which is [onFailure]. Both stay on the form with the edits still in it.
     */
    fun proposeEntry(
        state: AcceptCuratedSuggestionUIState.Loaded,
        onSuccess: (sessionId: String) -> Unit,
        onInvalid: () -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits. Two sessions over two versions of one
        // entry would leave the list decided by whichever quorum finished last.
        if (isActionPending.value) return

        val template = state.schema.canonicalTemplate(
            values = draft(state),
            source = CuratedSuggestionRef.of(state.suggestion.entry),
        )

        problems = state.schema.verifyCanonical(template)
        if (problems.isNotEmpty()) {
            onInvalid.invoke()
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = state.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a canonical entry for ${state.schema.coordinate()}", error)
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
        private const val TAG = "AcceptCuratedSuggestionViewModel"

        /** The tag of the field the form keeps whatever else it says: the entry's coordinate. */
        private const val IDENTIFIER_TAG = "d"

        /**
         * The fields the form opens for editing: the ones the schema's form asks
         * for, less the one writing to `d`. See the class note for why those two
         * are kept as they were suggested.
         */
        fun editableFields(state: AcceptCuratedSuggestionUIState.Loaded): List<CuratedField> =
            state.schema.schema.formFields().filterNot { it.tag() == IDENTIFIER_TAG }

        /**
         * The fields the form shows but does not open: what the suggester's client
         * filled in, and what the entry is addressed by. In the schema's order,
         * and only the ones the suggestion has a value for, since a kept field
         * with nothing in it has nothing to show.
         */
        fun keptFields(state: AcceptCuratedSuggestionUIState.Loaded): List<CuratedField> {
            val editable = editableFields(state).map { it.name }.toSet()
            val values = state.suggestion.entry.values

            return state.schema.schema.fields.filter { it.name !in editable && values[it.name].orEmpty().isNotEmpty() }
        }

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            identifier: String,
            suggestionId: String,
            initialAcceptCuratedSuggestionUIState: AcceptCuratedSuggestionUIState =
                AcceptCuratedSuggestionUIState.Loading,
            nostrRepository: NostrRepository,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AcceptCuratedSuggestionViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    identifier = identifier,
                    suggestionId = suggestionId,
                    initialAcceptCuratedSuggestionUIState = initialAcceptCuratedSuggestionUIState,
                    nostrRepository = nostrRepository,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
