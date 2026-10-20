package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.a_date_as_yyyy_yyyy_mm_or_yyyy_mm_dd
import bennu.composeapp.generated.resources.a_whole_number
import bennu.composeapp.generated.resources.add_another
import bennu.composeapp.generated.resources.at_least_n
import bennu.composeapp.generated.resources.at_least_one_of_these_is_needed
import bennu.composeapp.generated.resources.at_most_n
import bennu.composeapp.generated.resources.at_most_n_characters
import bennu.composeapp.generated.resources.could_not_ask_the_group_to_sign_this_entry
import bennu.composeapp.generated.resources.field_and_problem
import bennu.composeapp.generated.resources.filled_in_by_the_suggester_s_app_and_kept
import bennu.composeapp.generated.resources.kept_from_the_suggestion
import bennu.composeapp.generated.resources.more_than_zero
import bennu.composeapp.generated.resources.must_match_the_pattern
import bennu.composeapp.generated.resources.n_or_earlier
import bennu.composeapp.generated.resources.n_or_later
import bennu.composeapp.generated.resources.one_word_without_spaces
import bennu.composeapp.generated.resources.only_one_value_for_this_field
import bennu.composeapp.generated.resources.pick_one_of_the_options
import bennu.composeapp.generated.resources.propose_entry
import bennu.composeapp.generated.resources.suggested_by_on
import bennu.composeapp.generated.resources.take_this_suggestion_into_the_list
import bennu.composeapp.generated.resources.that_is_not_a_web_address
import bennu.composeapp.generated.resources.that_must_be_an_https_address
import bennu.composeapp.generated.resources.the_entry_cannot_be_signed_as_it_is
import bennu.composeapp.generated.resources.the_group_already_has_an_entry_for_this
import bennu.composeapp.generated.resources.the_group_signs_an_entry_so_it_takes_a_quorum
import bennu.composeapp.generated.resources.this_entry_cannot_be_signed_as_it_is
import bennu.composeapp.generated.resources.this_field_is_required
import bennu.composeapp.generated.resources.this_group_has_no_shared_key_to_sign_an_entry
import bennu.composeapp.generated.resources.this_list_uses_a_field_type_this_app_does_not_support
import org.jetbrains.compose.resources.stringResource
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.extensions.toFormattedTimeAndDateString
import com.machankura.bennu.compose.nostr.CuratedSuggestion
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedEntry
import com.machankura.bennu.compose.nostr.curated.CuratedEntryProblem
import com.machankura.bennu.compose.nostr.curated.CuratedField
import com.machankura.bennu.compose.nostr.curated.CuratedFieldConfig
import com.machankura.bennu.compose.nostr.curated.CuratedFieldType
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.NostrRepository
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.Decorative
import com.machankura.bennu.compose.ui.composable.widgets.ErrorState
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.bennu.compose.ui.composable.widgets.rememberNotifier
import com.machankura.bennu.compose.ui.theme.ConformancePreviews
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.theme.spacing
import com.machankura.bennu.compose.ui.view.model.AcceptCuratedSuggestionViewModel
import com.machankura.bennu.compose.ui.view.state.AcceptCuratedSuggestionUIState
import kotlin.time.Instant

/**
 * The form for taking one suggestion into a list the group curates.
 *
 * The queue screen shows what was suggested; this is where the group decides
 * what the list will say. Every field the schema's form asks for is an input
 * seeded from the suggestion, because curation is editorial -- the NIP lets
 * the curator fix a year or swap a poster on the way through, and a button
 * that could only say yes would leave a typo in the list forever. What the
 * form does not ask for it shows: the identifier, which the entry keeps so
 * that accepting it twice revises one entry rather than making two, and the
 * fields the suggester's app filled in, which this app has no way to fill in
 * itself.
 *
 * **The button proposes rather than saves.** The entry goes out as one kind
 * 31890 for the room's quorum to put its key to, pointing back at the
 * suggestion it came from, and the screen hands over to the signing session it
 * opened. Nothing about the list has changed by the time this screen closes.
 * The form is driven by a schema this screen has never seen, so a value the
 * schema refuses is named under its own input rather than found out after a
 * quorum has signed.
 */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun AcceptCuratedSuggestionScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    identifier: String,
    suggestionId: String,
    initialAcceptCuratedSuggestionUIState: AcceptCuratedSuggestionUIState =
        AcceptCuratedSuggestionUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateBack: () -> Unit,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
) {
    val acceptCuratedSuggestionViewModel: AcceptCuratedSuggestionViewModel = viewModel(
        factory = AcceptCuratedSuggestionViewModel.factory(
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
    )

    LaunchedEffect(true) {
        if (initialAcceptCuratedSuggestionUIState == AcceptCuratedSuggestionUIState.Loading) {
            acceptCuratedSuggestionViewModel.initiate()
        }
    }

    // Read out here rather than in a click handler: both are composable and an
    // onClick lambda is not. The scope is the caller's so a message survives this
    // screen being replaced by the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_this_entry)

    ScreenStateTransition(acceptCuratedSuggestionViewModel.acceptCuratedSuggestionUIState) { uiState ->
        when (val state = uiState) {
            is AcceptCuratedSuggestionUIState.Error -> {
                // Nothing to retry: the room, the schema and the suggestion came
                // from navigation arguments, and reading them again with the
                // same ones fails the same way.
                ErrorState(message = state.message, onRetry = null)
            }

            AcceptCuratedSuggestionUIState.Loading -> {
                LoadingDataIndicator()
            }

            is AcceptCuratedSuggestionUIState.Loaded -> {
                val canSign = state.canSign

                // The inputs come from the state rather than from whatever loaded
                // it, so a screen handed a loaded state -- a preview, a layout
                // test -- fills in the same as the app does. Seeding runs once.
                LaunchedEffect(state) {
                    acceptCuratedSuggestionViewModel.seedWorkingCopy(state)
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = { state.localChatRoom.RenderChatRoomTitleText() },
                            navigationIcon = { NavigateBackButton(onNavigateBack) }
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_entry),
                                    icon = { Icon(Icons.Default.Draw, contentDescription = "Propose entry") },
                                    enabled = canSign,
                                    onClick = {
                                        acceptCuratedSuggestionViewModel.proposeEntry(
                                            state = state,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back
                                                // to the queue. The entry is not in
                                                // the list yet -- it goes in when a
                                                // quorum signs -- so landing on a
                                                // queue still showing the row as
                                                // waiting would read as a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            // The reasons are drawn on the form
                                            // itself, under the inputs they are
                                            // about; a snackbar would name a
                                            // problem and scroll away from it.
                                            onInvalid = {},
                                            // Stays on the form with the edits still
                                            // in it: a failure to open a session is
                                            // no reason to throw away the typing.
                                            onFailure = { notify(couldNotPropose) }
                                        )
                                    }
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    AcceptCuratedSuggestionForm(
                        state = state,
                        viewModel = acceptCuratedSuggestionViewModel,
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * The form under the bars: what is being taken up and into what, the reasons
 * it cannot be as it stands, an input per field the schema asks for, and the
 * fields it keeps.
 *
 * A lazy list rather than a scrolling column, the way the schema editor is,
 * because the number of inputs is the schema's to decide and a long form is
 * still one screen. Split out so a layout test can render it without the bars.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AcceptCuratedSuggestionForm(
    state: AcceptCuratedSuggestionUIState.Loaded,
    viewModel: AcceptCuratedSuggestionViewModel,
    modifier: Modifier = Modifier
) {
    val schema = state.schema.schema
    val suggestion = state.suggestion
    val editable = AcceptCuratedSuggestionViewModel.editableFields(state)
    val kept = AcceptCuratedSuggestionViewModel.keptFields(state)
    val entryProblems = viewModel.problemsAboutTheEntry(state)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.screenMargin,
            vertical = MaterialTheme.spacing.itemGap
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = suggestion.entry.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        item {
            Text(
                text = stringResource(
                    Res.string.suggested_by_on,
                    suggestion.suggesterName(),
                    suggestion.suggestedAt.toFormattedTimeAndDateString()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        item {
            Text(
                text = stringResource(Res.string.take_this_suggestion_into_the_list, schema.displayName()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        item {
            Text(
                text = stringResource(Res.string.the_group_signs_an_entry_so_it_takes_a_quorum),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Said up front rather than left to the queue's mark: a member who did
        // not notice would propose what they took for a new entry and get a
        // revision, which is the right outcome but a surprising one.
        if (suggestion.isCurated) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = Decorative,
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))

                    Text(
                        text = stringResource(Res.string.the_group_already_has_an_entry_for_this),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (!state.canSign) {
            item {
                Text(
                    text = stringResource(Res.string.this_group_has_no_shared_key_to_sign_an_entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        // The problems no input can carry: about the entry as a whole, or about
        // a field the form keeps rather than asks for. Named, and all at once,
        // for the reason the schema editor gives -- a button that does nothing
        // is indistinguishable from a missed tap.
        if (entryProblems.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    Text(
                        text = stringResource(Res.string.this_entry_cannot_be_signed_as_it_is),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )

                    entryProblems.forEach { problem ->
                        val field = schema.fields.firstOrNull { it.name == problem.field }
                        val unsupported = schema.unsupportedFields.firstOrNull { it.name == problem.field }

                        Text(
                            text = stringResource(
                                Res.string.field_and_problem,
                                field?.labelOrName() ?: unsupported?.labelOrName() ?: problem.field,
                                problem.message(field, schema)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        editable.forEach { field ->
            item(key = field.name) {
                val inputs = viewModel.inputs[field.name].orEmpty()
                val problem = viewModel.problemsFor(field.name).firstOrNull()

                EntryFieldEditor(
                    field = field,
                    schema = schema,
                    inputs = inputs,
                    problem = problem,
                    onAddValue = { viewModel.addValue(field.name) },
                    onRemoveValue = { index -> viewModel.removeValue(field.name, index) }
                )
            }
        }

        if (kept.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))

                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(Res.string.kept_from_the_suggestion),
                        style = MaterialTheme.typography.titleSmall
                    )

                    Text(
                        text = stringResource(Res.string.filled_in_by_the_suggester_s_app_and_kept),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            kept.forEach { field ->
                item(key = "kept:${field.name}") {
                    KeptField(field = field, values = suggestion.entry.values[field.name].orEmpty())
                }
            }
        }
    }
}

/**
 * The inputs for one field, in the shape its type asks for.
 *
 * An enum is its options as chips, one selected, because typing a word that
 * has to be one of six is a way to misspell it; everything else is a text
 * field with the keyboard its type wants. A field that repeats is one input
 * per value with a way to drop each and a way to add one, since the
 * suggestion may have carried two watch links and the list may want a third.
 * The schema's hint sits under the input until a problem takes its place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryFieldEditor(
    field: CuratedField,
    schema: CuratedSchema,
    inputs: List<TextFieldState>,
    problem: CuratedEntryProblem?,
    onAddValue: () -> Unit,
    onRemoveValue: (index: Int) -> Unit
) {
    val message = problem?.message(field, schema)
    val options = field.config.options

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        if (field.type == CuratedFieldType.Enum && !options.isNullOrEmpty()) {
            EnumInput(field = field, options = options, input = inputs.firstOrNull(), message = message)
        } else {
            inputs.forEachIndexed { index, input ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EntryTextField(
                        modifier = Modifier.weight(1f),
                        state = input,
                        field = field,
                        // A problem is about the field, and the field is every
                        // input; the first is where a reader's eye lands.
                        message = message.takeIf { index == 0 },
                        isError = problem != null
                    )

                    if (field.config.repeat == true) {
                        IconButton(onClick = { onRemoveValue(index) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove this value",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (field.config.repeat == true) {
                TextButton(onClick = onAddValue) {
                    Icon(Icons.Default.Add, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.add_another))
                }
            }
        }
    }
}

/**
 * An enum as chips. The selection is written into the field's one input, so
 * the draft reads an enum the way it reads everything else; a value the
 * suggestion carried that is not among the options is shown as an unselected
 * set with the problem under it, rather than quietly dropped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnumInput(
    field: CuratedField,
    options: List<String>,
    input: TextFieldState?,
    message: String?
) {
    val selected = input?.text?.toString()

    Text(
        text = field.labelOrName(),
        style = MaterialTheme.typography.labelMedium,
        color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { input?.setTextAndPlaceCursorAtEnd(option) },
                label = { Text(option) }
            )
        }
    }

    (message ?: field.config.hint)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One text input of the form, in the shape the app's other forms use: the
 * borderless outlined field over the bottom bar's tint, labelled as the
 * schema labels it and placeholdered as the schema says.
 *
 * The keyboard follows the type -- digits for a year, a URL keyboard for a
 * link, and the plain one for a date, whose `2010-07` needs a hyphen the
 * number pad does not always have -- and a long text gets room to be one. The
 * supporting line is the problem when there is one and the schema's hint when
 * there is not.
 */
@Composable
private fun EntryTextField(
    state: TextFieldState,
    field: CuratedField,
    message: String?,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    val keyboardType = when (field.type) {
        CuratedFieldType.Year, CuratedFieldType.Duration, CuratedFieldType.Number -> KeyboardType.Number
        CuratedFieldType.Url, CuratedFieldType.Image -> KeyboardType.Uri
        else -> KeyboardType.Text
    }

    OutlinedTextField(
        modifier = modifier.fillMaxWidth().background(BottomAppBarDefaults.containerColor),
        state = state,
        lineLimits = if (field.type == CuratedFieldType.LongText) {
            TextFieldLineLimits.MultiLine(minHeightInLines = 3)
        } else {
            TextFieldLineLimits.SingleLine
        },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent
        ),
        label = {
            Text(text = field.labelOrName())
        },
        placeholder = field.placeholder.takeIf { it.isNotBlank() }?.let { { Text(text = it) } },
        supportingText = (message ?: field.config.hint)?.let { { Text(text = it) } }
    )
}

/**
 * A field the form keeps rather than asks for: the schema's label over the
 * values as the suggestion carried them, the way the queue's sheet shows an
 * entry. Monospaced where the value is a thing rather than prose.
 */
@Composable
private fun KeptField(field: CuratedField, values: List<String>) {
    val isMonospaced = when (field.type) {
        CuratedFieldType.Token, CuratedFieldType.Url, CuratedFieldType.Image -> true
        else -> false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = field.labelOrName(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = values.joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (isMonospaced) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * One problem in words, for the input it is about.
 *
 * The bounds are read off the field's config, since "at most 500" says
 * something and "too long" does not; a `require-any` rule names the other
 * fields it could have been satisfied by, as the schema labels them.
 */
@Composable
internal fun CuratedEntryProblem.message(field: CuratedField?, schema: CuratedSchema): String = when (reason) {
    CuratedEntryProblem.Reason.Missing,
    CuratedEntryProblem.Reason.Empty -> stringResource(Res.string.this_field_is_required)

    CuratedEntryProblem.Reason.Repeated -> stringResource(Res.string.only_one_value_for_this_field)
    CuratedEntryProblem.Reason.NotOneWord -> stringResource(Res.string.one_word_without_spaces)
    CuratedEntryProblem.Reason.NotAUrl -> stringResource(Res.string.that_is_not_a_web_address)
    CuratedEntryProblem.Reason.NotHttps -> stringResource(Res.string.that_must_be_an_https_address)
    CuratedEntryProblem.Reason.NotAnOption -> stringResource(Res.string.pick_one_of_the_options)
    CuratedEntryProblem.Reason.NotAWholeNumber -> stringResource(Res.string.a_whole_number)
    CuratedEntryProblem.Reason.NotPositive -> stringResource(Res.string.more_than_zero)
    CuratedEntryProblem.Reason.NotADate -> stringResource(Res.string.a_date_as_yyyy_yyyy_mm_or_yyyy_mm_dd)

    // A date's bounds are years, and "at least 2009" is not how anyone says it.
    CuratedEntryProblem.Reason.BelowMinimum -> stringResource(
        if (field?.type == CuratedFieldType.Date) Res.string.n_or_later else Res.string.at_least_n,
        field?.config?.min?.toString() ?: ""
    )

    CuratedEntryProblem.Reason.AboveMaximum -> stringResource(
        if (field?.type == CuratedFieldType.Date) Res.string.n_or_earlier else Res.string.at_most_n,
        field?.config?.max?.toString() ?: ""
    )

    CuratedEntryProblem.Reason.TooLong ->
        stringResource(Res.string.at_most_n_characters, field?.config?.max?.toString() ?: "")

    CuratedEntryProblem.Reason.PatternMismatch ->
        stringResource(Res.string.must_match_the_pattern, field?.config?.pattern ?: "")

    CuratedEntryProblem.Reason.RequireAnyUnmet -> stringResource(
        Res.string.at_least_one_of_these_is_needed,
        schema.requireAny.firstOrNull { it.firstOrNull() == this.field }
            .orEmpty()
            .joinToString { name -> schema.fields.firstOrNull { it.name == name }?.labelOrName() ?: name }
    )

    // The one problem the schema has and the entry does not: a required field
    // is of a type this build cannot write, named so the member knows which.
    CuratedEntryProblem.Reason.UnsupportedType -> stringResource(
        Res.string.this_list_uses_a_field_type_this_app_does_not_support,
        schema.unsupportedFields.firstOrNull { it.name == this.field }?.type ?: ""
    )

    // None of these can come out of the form: the builder writes the kind, the
    // root and the source, and the group is the curator. Named all the same,
    // since a check that cannot fail is the one that does.
    CuratedEntryProblem.Reason.WrongKind,
    CuratedEntryProblem.Reason.NotAReply,
    CuratedEntryProblem.Reason.RepliesElsewhere,
    CuratedEntryProblem.Reason.NotPermitted,
    CuratedEntryProblem.Reason.NotTheCurator,
    CuratedEntryProblem.Reason.SourceMalformed -> stringResource(Res.string.the_entry_cannot_be_signed_as_it_is)
}

@ConformancePreviews
@Composable
private fun AcceptCuratedSuggestionScreenPreview() {
    val chatRoomId = "d4c3b2a1".repeat(8)
    val suggester = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"

    val schema = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = "c".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
        schema = CuratedSchema(
            identifier = "films",
            title = "Film suggestion",
            name = "Films worth translating",
            description = "Films the group would subtitle.",
            visibility = CuratedVisibility.Public,
            fields = listOf(
                CuratedField.IDENTIFIER,
                CuratedField.TITLE,
                CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year"),
                CuratedField(
                    "type",
                    CuratedFieldType.Enum,
                    isRequired = true,
                    label = "Type",
                    config = CuratedFieldConfig(options = listOf("movie", "documentary", "short"))
                ),
                CuratedField(
                    "watchUrl",
                    CuratedFieldType.Url,
                    isRequired = false,
                    label = "Watch URL",
                    config = CuratedFieldConfig(tag = "r", marker = "watch", repeat = true)
                ),
                CuratedField(
                    "description",
                    CuratedFieldType.LongText,
                    isRequired = false,
                    label = "Why",
                    config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG)
                ),
                CuratedField(
                    "hashtags",
                    CuratedFieldType.Token,
                    isRequired = false,
                    label = "Hashtags",
                    config = CuratedFieldConfig(tag = "t", repeat = true, derived = true)
                ),
            ),
            relays = listOf("wss://relay.one.example")
        )
    )

    val suggestion = CuratedSuggestion(
        entry = CuratedEntry(
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            id = "a".repeat(64),
            pubKey = suggester,
            createdAt = 1_700_000_900,
            identifier = "the-rise-and-rise-of-bitcoin-2014",
            title = "The Rise and Rise of Bitcoin",
            values = mapOf(
                "identifier" to listOf("the-rise-and-rise-of-bitcoin-2014"),
                "title" to listOf("The Rise and Rise of Bitcoin"),
                "year" to listOf("2014"),
                "type" to listOf("documentary"),
                "watchUrl" to listOf("http://bitcoindoc.com/"),
                "description" to listOf("Follows the early Bitcoin community from 2011 onward."),
                "hashtags" to listOf("bitcoin", "documentary"),
            )
        ),
        event = NostrEvent(
            id = "a".repeat(64),
            pubKey = suggester,
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            tags = emptyArray(),
            content = "",
            sig = "b".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_900)
        ),
        suggester = null,
        isCurated = false
    )

    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AcceptCuratedSuggestionScreen(
                activeUserPublicKey = "u".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                identifier = "films",
                suggestionId = suggestion.id,
                initialAcceptCuratedSuggestionUIState = AcceptCuratedSuggestionUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = chatRoomId,
                            userPublicKey = "u".repeat(64),
                            subject = "Translation room",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        )
                    ),
                    schema = schema,
                    suggestion = suggestion,
                    canSign = true
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateBack = {},
                onNavigateToRouteAndPopUpInclusive = {}
            )
        }
    }
}
