package to.curare.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.bech32ToHexOrNull
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedSchemaProblem
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.view.state.EditGroupCuratedSchemaUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * One of the group's curated schemas, edited locally and proposed as one event.
 *
 * Nothing here is saved. The parts of the schema that are lists -- its fields,
 * its rules, its suggesters, its relays -- live on this view model as snapshot
 * state so that a bottom sheet opening over the form, or the window turning,
 * does not throw an edit away; the identity fields are text and live in the
 * composition like every other form's. The button folds both halves into a
 * [CuratedSchema], refuses it for any reason a reader would, and otherwise opens
 * one signing session over it. The schema changes on every member's device at
 * once, when a quorum has signed, or not at all.
 *
 * ### Edit and replace
 *
 * Kind 31889 is addressable, so proposing a schema under an identifier the group
 * already signed one for *replaces* it -- which is what an edit is, and why the
 * identifier is the one thing the editor will not let an edit change. A new
 * identifier is a new list, with a queue of its own, and the group's screen
 * offers that as adding a schema rather than as editing this one.
 *
 * The comparison for "nothing changed" is against the schema as the group signed
 * it, normalized on both sides, because re-signing an identical schema would put
 * a second statement on the record with a newer timestamp: harmless, since the
 * kind is replaceable, and still a lie about when the group last decided
 * anything about the list.
 */
class EditGroupCuratedSchemaViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val relayHint: String?,
    /** The `d` of the schema being edited, or null for a new one. */
    val identifier: String?,
    initialEditGroupCuratedSchemaUIState: EditGroupCuratedSchemaUIState,
    val chatRepository: ChatRepository,
    val frostSigningRepository: FrostSigningRepository,
): ViewModel() {

    var editGroupCuratedSchemaUIState: EditGroupCuratedSchemaUIState by mutableStateOf(
        initialEditGroupCuratedSchemaUIState
    )
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Who may suggest. Always set: the editor never proposes a schema without one. */
    var visibility: CuratedVisibility by mutableStateOf(CuratedVisibility.Public)
        private set

    /** The working copy of the schema's fields, in the order they will be written. */
    val fields = mutableStateListOf<CuratedField>()

    /**
     * The working copy of the `require-any` rules, each a set of field names.
     *
     * A rule may hold fewer than two names while it is being built; one that
     * still does when the button is pressed says nothing and is dropped rather
     * than refused -- see `CuratedSchema.requireAny`.
     */
    val requireAny = mutableStateListOf<List<String>>()

    /** The working copy of the `p` tags: pubkeys besides the group that may suggest. */
    val suggesters = mutableStateListOf<HexKey>()

    /** The working copy of the `relay` tags. */
    val relays = mutableStateListOf<String>()

    /** The field the sheet is open on, or null while it is closed. */
    var editingField: FieldEdit? by mutableStateOf(null)
        private set

    /**
     * Why the last proposal did not go out, or nothing. Set by the button and
     * left showing until the next press, since the button is the thing that
     * will clear it.
     */
    var problems: List<CuratedSchemaProblem> by mutableStateOf(emptyList())
        private set

    /** Why the last relay was refused, or null. Cleared by the next keystroke. */
    var addRelayFailure: AddRelayFailure? by mutableStateOf(null)
        private set

    /** Why the last suggester was refused, or null. Cleared by the next keystroke. */
    var addSuggesterFailure: AddSuggesterFailure? by mutableStateOf(null)
        private set

    private var isSeeded = false

    /**
     * A field on the sheet. [index] is its place in [fields], or null for one
     * being added -- which is also what decides whether committing it appends
     * or replaces.
     */
    data class FieldEdit(val index: Int?, val field: CuratedField)

    enum class AddRelayFailure {
        /** Not a `wss://` URL, or one pointing at this machine. */
        NotARelay,

        /** Already named, which is a no-op rather than a mistake. */
        AlreadyListed,
    }

    enum class AddSuggesterFailure {
        /** Neither an npub nor 64 hex characters. */
        NotAPubkey,

        AlreadyListed,
    }

    /** Why a field cannot be committed as it stands. */
    enum class FieldProblem {
        NameMissing,

        /** A field name is the key a form collects a value under, and a key is one word. */
        NameHasSpaces,

        /** Another field already answers to that name. */
        NameTaken,
    }

    fun initiateEditGroupCuratedSchema() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            editGroupCuratedSchemaUIState = if (localChatRoom == null) {
                EditGroupCuratedSchemaUIState.Error("Couldn't find the chat room")
            } else {
                val existing = identifier?.let { wanted ->
                    chatRepository.groupCuratedSchemas(chatRoomId).firstOrNull { it.identifier == wanted }
                }

                if (identifier != null && existing == null) {
                    // Opened on a schema this device does not hold. Nothing to
                    // retry and nothing to edit; offering a blank form under that
                    // identifier would propose a replacement for a list nobody
                    // here has read.
                    EditGroupCuratedSchemaUIState.Error("Couldn't find that schema")
                } else {
                    EditGroupCuratedSchemaUIState.Loaded(
                        localChatRoom = localChatRoom,
                        existing = existing,
                        defaultRelays = defaultRelays(chatRoomId),
                        canSign = localChatRoom.chatRoom.mlsGroupState != null &&
                            frostSigningRepository.canSign(chatRoomId),
                    )
                }
            }
        }
    }

    /**
     * Where a new schema says its list lives: the relays the group has said it
     * writes to, since that is where its canonical entries would be read from.
     *
     * The NIP-65 list where the group has agreed one, else that set's defaults
     * -- which is this build's own relay, and the same thing a group opening the
     * relay editor for the first time is shown. A schema with no relays is one
     * whose suggestions could go anywhere, so the seed is worth getting right.
     */
    private suspend fun defaultRelays(chatRoomId: String): List<String> {
        val general = chatRepository.groupRelayLists(chatRoomId)
            .firstOrNull { it.set == GroupRelaySet.General }
            ?: return GroupRelaySet.General.defaults().map { it.url.url }

        return (if (general.isAgreed) general.relays else general.set.defaults())
            .filter { it.write }
            .map { it.url.url }
    }

    /**
     * Fills the working copy from the schema the group signed, once.
     *
     * An existing schema starts at itself. A new one starts at the two fields
     * every list has -- the identifier and the title, which `CuratedSchema.normalized`
     * would put back anyway, shown up front so that what the member sees is what
     * the group will sign -- and at the group's own relays.
     *
     * Called from the screen rather than from [initiateEditGroupCuratedSchema],
     * because the loader is not the only way a loaded state arrives: a preview
     * and a layout test both hand one straight in. Runs once, so the effect that
     * calls it re-firing does not undo an edit.
     */
    fun seedWorkingCopy(state: EditGroupCuratedSchemaUIState.Loaded) {
        if (isSeeded) return
        isSeeded = true

        val schema = state.existing?.schema
        if (schema != null) {
            visibility = schema.visibility ?: CuratedVisibility.Public
            fields.addAll(schema.fields)
            requireAny.addAll(schema.requireAny)
            suggesters.addAll(schema.suggesters)
            relays.addAll(schema.relays)
        } else {
            visibility = CuratedVisibility.Public
            fields.addAll(listOf(CuratedField.IDENTIFIER, CuratedField.TITLE))
            relays.addAll(state.defaultRelays)
        }
    }

    fun selectVisibility(visibility: CuratedVisibility) {
        this.visibility = visibility
    }

    /**
     * Whether [field] is one of the two every list must have.
     *
     * Decided by the tag it writes to, not its name, because that is what the
     * NIP's rule is about: a list needs a field writing to `d` and one writing
     * to `title`, whatever they are called. Such a field cannot be removed or
     * made optional here -- `CuratedSchema.normalized` would only put it back.
     */
    fun isMandatory(field: CuratedField): Boolean =
        CuratedField.MANDATORY.any { (tag, _) -> field.tag() == tag }

    /** Opens the sheet on a blank field. Text, because most fields are. */
    fun startAddingField() {
        editingField = FieldEdit(
            index = null,
            field = CuratedField(name = "", type = CuratedFieldType.Text, isRequired = false),
        )
    }

    fun startEditingField(index: Int) {
        fields.getOrNull(index)?.let { editingField = FieldEdit(index = index, field = it) }
    }

    fun dismissFieldEditor() {
        editingField = null
    }

    /**
     * Puts [field] into the working copy at the place the sheet was opened on,
     * or says why it cannot.
     *
     * Null means it went in and the sheet is closed. A rename follows through to
     * the rules that named the field, since a rule naming a field that no longer
     * exists would silently never be satisfied.
     */
    fun commitField(field: CuratedField): FieldProblem? {
        val edit = editingField ?: return null

        val name = field.name.trim()
        if (name.isEmpty()) return FieldProblem.NameMissing
        if (name.any { it.isWhitespace() }) return FieldProblem.NameHasSpaces
        if (fields.withIndex().any { (index, other) -> index != edit.index && other.name == name }) {
            return FieldProblem.NameTaken
        }

        val committed = field.copy(name = name)

        if (edit.index == null) {
            fields.add(committed)
        } else {
            val previous = fields[edit.index]
            fields[edit.index] = committed
            if (previous.name != name) {
                requireAny.indices.forEach { group ->
                    requireAny[group] = requireAny[group].map { if (it == previous.name) name else it }
                }
            }
        }

        editingField = null
        return null
    }

    /** Drops the field at [index] and takes it out of any rule that named it. */
    fun removeField(index: Int) {
        val removed = fields.getOrNull(index) ?: return
        if (isMandatory(removed)) return

        fields.removeAt(index)
        requireAny.indices.forEach { group ->
            requireAny[group] = requireAny[group].filterNot { it == removed.name }
        }
        editingField = null
    }

    fun addRequireAnyGroup() {
        requireAny.add(emptyList())
    }

    fun toggleRequireAny(group: Int, fieldName: String) {
        val names = requireAny.getOrNull(group) ?: return
        requireAny[group] = if (fieldName in names) names - fieldName else names + fieldName
    }

    fun removeRequireAnyGroup(group: Int) {
        if (group in requireAny.indices) requireAny.removeAt(group)
    }

    /**
     * Adds [input] to the suggesters, or says why it did not.
     *
     * True when the row appeared, which is the caller's cue to clear the field.
     */
    fun addSuggester(input: String): Boolean {
        val pubkey = pubkeyOrNull(input) ?: run {
            addSuggesterFailure = AddSuggesterFailure.NotAPubkey
            return false
        }

        if (pubkey in suggesters) {
            addSuggesterFailure = AddSuggesterFailure.AlreadyListed
            return false
        }

        suggesters.add(pubkey)
        addSuggesterFailure = null
        return true
    }

    fun removeSuggester(pubkey: HexKey) {
        suggesters.remove(pubkey)
    }

    fun clearAddSuggesterFailure() {
        addSuggesterFailure = null
    }

    /**
     * Adds [url] to the relays, or says why it did not.
     *
     * Held to the same rule as the group's relay lists -- `wss://`, not this
     * machine -- rather than to the NIP's looser one, because this is a relay
     * the group is about to sign for strangers to publish to. See
     * `GroupRelaySet.relayUrlOrNull`.
     */
    fun addRelay(url: String): Boolean {
        val relay = GroupRelaySet.relayUrlOrNull(url) ?: run {
            addRelayFailure = AddRelayFailure.NotARelay
            return false
        }

        // Compared normalized, because a relay signed into an existing schema is
        // kept as it was written and the normalizer adds a trailing slash: the
        // same host with and without one is one relay, not two.
        if (relays.any { GroupRelaySet.relayUrlOrNull(it) == relay }) {
            addRelayFailure = AddRelayFailure.AlreadyListed
            return false
        }

        relays.add(relay.url)
        addRelayFailure = null
        return true
    }

    fun removeRelay(url: String) {
        relays.remove(url)
    }

    fun clearAddRelayFailure() {
        addRelayFailure = null
    }

    /**
     * The schema as the form has it: the text fields trimmed and the lists as
     * they stand, with a rule too short to mean anything left out.
     */
    fun draft(
        identifier: String,
        title: String,
        name: String,
        description: String,
        picture: String,
        domain: String,
    ): CuratedSchema = CuratedSchema(
        identifier = identifier.trim(),
        title = title.trim(),
        name = name.trim(),
        description = description.trim(),
        visibility = visibility,
        picture = picture.trim().ifEmpty { null },
        domain = domain.trim().ifEmpty { null }?.let(CuratedSchemaEvent::normalizeDomain),
        fields = fields.toList(),
        requireAny = requireAny.filter { it.size > 1 },
        suggesters = suggesters.toList(),
        relays = relays.toList(),
    )

    /**
     * Opens a session over the schema, or says why not.
     *
     * Three refusals before anything is proposed and each is the caller's to
     * say: a schema a reader would reject, which is [onInvalid] with the
     * reasons; an edit that changed nothing, which is [onNothingToPropose]; and
     * a proposal that could not be opened, which is [onFailure]. All of them
     * stay on the form with the edits still in it.
     */
    fun proposeSchema(
        localChatRoom: LocalChatRoom,
        existing: GroupCuratedSchema?,
        identifier: String,
        title: String,
        name: String,
        description: String,
        picture: String,
        domain: String,
        onSuccess: (sessionId: String) -> Unit,
        onInvalid: () -> Unit,
        onNothingToPropose: () -> Unit,
        onFailure: () -> Unit
    ) {
        // Guard against double submits. Two sessions over two versions of one
        // schema would leave the list's form decided by whichever quorum
        // finished last.
        if (isActionPending.value) return

        val schema = draft(
            // The identifier of an existing schema is the coordinate every
            // suggestion to it replies to, so an edit keeps it whatever the
            // field says -- and the field is read-only to say so.
            identifier = existing?.identifier ?: identifier,
            title = title,
            name = name,
            description = description,
            picture = picture,
            domain = domain,
        )

        problems = schema.problems()
        if (problems.isNotEmpty()) {
            onInvalid.invoke()
            return
        }

        if (existing != null && existing.schema == schema.normalized()) {
            onNothingToPropose.invoke()
            return
        }

        isActionPending.value = true

        val template = GroupCuratedSchema.template(schema)

        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching {
                frostSigningRepository.proposeSigning(
                    localChatRoom = localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                )
            }.onFailure { error ->
                logger.e("Failed to propose a curated schema for $chatRoomId", error)
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
        private const val TAG = "EditGroupCuratedSchemaViewModel"

        /**
         * [input] as a pubkey the schema could name, or null.
         *
         * An npub -- with or without a `nostr:` in front, the way it is pasted
         * from most clients -- or 64 hex characters in either case. Nothing else,
         * and in particular not a NIP-05 address, since resolving one is a
         * network round trip and this is a field on a form.
         */
        fun pubkeyOrNull(input: String): HexKey? {
            val trimmed = input.trim().removePrefix("nostr:")

            val hex = if (trimmed.startsWith("npub1", ignoreCase = true)) {
                trimmed.bech32ToHexOrNull()
            } else {
                trimmed.lowercase()
            }

            return hex?.takeIf { candidate ->
                candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' }
            }
        }

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            relayHint: String?,
            identifier: String?,
            initialEditGroupCuratedSchemaUIState: EditGroupCuratedSchemaUIState =
                EditGroupCuratedSchemaUIState.Loading,
            chatRepository: ChatRepository,
            frostSigningRepository: FrostSigningRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                EditGroupCuratedSchemaViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    chatRoomId = chatRoomId,
                    relayHint = relayHint,
                    identifier = identifier,
                    initialEditGroupCuratedSchemaUIState = initialEditGroupCuratedSchemaUIState,
                    chatRepository = chatRepository,
                    frostSigningRepository = frostSigningRepository
                )
            }
        }
    }
}
