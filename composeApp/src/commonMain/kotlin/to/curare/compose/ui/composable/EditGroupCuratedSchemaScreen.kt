package to.curare.compose.ui.composable

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Ballot
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedSchemaProblem
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.composable.navigation.routes.FrostSigningRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import to.curare.compose.ui.composable.widgets.rememberNotifier
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.EditGroupCuratedSchemaViewModel
import to.curare.compose.ui.view.model.EditGroupCuratedSchemaViewModel.AddRelayFailure
import to.curare.compose.ui.view.model.EditGroupCuratedSchemaViewModel.AddSuggesterFailure
import to.curare.compose.ui.view.model.EditGroupCuratedSchemaViewModel.FieldEdit
import to.curare.compose.ui.view.model.EditGroupCuratedSchemaViewModel.FieldProblem
import to.curare.compose.ui.view.state.EditGroupCuratedSchemaUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.a_description_is_required
import curare.composeapp.generated.resources.a_domain_replaces_the_name_wherever_the_list_is_shown
import curare.composeapp.generated.resources.a_field_name_is_one_word
import curare.composeapp.generated.resources.a_field_named_that_already_exists
import curare.composeapp.generated.resources.a_field_needs_a_name
import curare.composeapp.generated.resources.a_name_is_required
import curare.composeapp.generated.resources.a_regular_expression_the_whole_value_must_match
import curare.composeapp.generated.resources.a_schema_needs_at_least_one_field
import curare.composeapp.generated.resources.a_title_is_required
import curare.composeapp.generated.resources.add_field
import curare.composeapp.generated.resources.add_rule
import curare.composeapp.generated.resources.add_schema
import curare.composeapp.generated.resources.an_entry_must_have_this_field
import curare.composeapp.generated.resources.an_identifier_is_required
import curare.composeapp.generated.resources.at_least_one_of
import curare.composeapp.generated.resources.could_not_ask_the_group_to_sign_this_schema
import curare.composeapp.generated.resources.defaults_to_the_field_name_content_means_the_body
import curare.composeapp.generated.resources.derived
import curare.composeapp.generated.resources.description
import curare.composeapp.generated.resources.domain
import curare.composeapp.generated.resources.done
import curare.composeapp.generated.resources.edit_field
import curare.composeapp.generated.resources.edit_schema
import curare.composeapp.generated.resources.eg_1900
import curare.composeapp.generated.resources.eg_200
import curare.composeapp.generated.resources.eg_2100
import curare.composeapp.generated.resources.eg_a_za_z_2_8
import curare.composeapp.generated.resources.eg_bitcoin_mov
import curare.composeapp.generated.resources.eg_movie_documentary_short
import curare.composeapp.generated.resources.eg_bitcoin_mov_suggestion
import curare.composeapp.generated.resources.eg_example_com
import curare.composeapp.generated.resources.eg_fields_for_a_bitcoin_mov_entry
import curare.composeapp.generated.resources.eg_https_example_com_group_png
import curare.composeapp.generated.resources.eg_https_youtube_com_watch
import curare.composeapp.generated.resources.eg_watch
import curare.composeapp.generated.resources.eg_watch_reference_url
import curare.composeapp.generated.resources.eg_watchurl
import curare.composeapp.generated.resources.eg_wss_relay_example_com
import curare.composeapp.generated.resources.field_name
import curare.composeapp.generated.resources.field_type_duration
import curare.composeapp.generated.resources.field_type_enum
import curare.composeapp.generated.resources.field_type_image
import curare.composeapp.generated.resources.field_type_longtext
import curare.composeapp.generated.resources.field_type_number
import curare.composeapp.generated.resources.field_type_text
import curare.composeapp.generated.resources.field_type_token
import curare.composeapp.generated.resources.field_type_url
import curare.composeapp.generated.resources.field_type_year
import curare.composeapp.generated.resources.fields
import curare.composeapp.generated.resources.filled_in_by_the_app_never_asked_for
import curare.composeapp.generated.resources.helper_text_under_the_input
import curare.composeapp.generated.resources.hint
import curare.composeapp.generated.resources.https_only
import curare.composeapp.generated.resources.identifier
import curare.composeapp.generated.resources.label
import curare.composeapp.generated.resources.marker
import curare.composeapp.generated.resources.maximum_characters
import curare.composeapp.generated.resources.maximum_value
import curare.composeapp.generated.resources.may_appear_more_than_once
import curare.composeapp.generated.resources.minimum_value
import curare.composeapp.generated.resources.name
import curare.composeapp.generated.resources.new_field
import curare.composeapp.generated.resources.no_relays_named_so_a_client_will_pick_its_own
import curare.composeapp.generated.resources.nothing_has_changed_to_propose
import curare.composeapp.generated.resources.npub_or_hex_pubkey
import curare.composeapp.generated.resources.one_of_the_relays_is_not_a_relay_address
import curare.composeapp.generated.resources.one_per_line_or_separated_by_commas
import curare.composeapp.generated.resources.optional
import curare.composeapp.generated.resources.options
import curare.composeapp.generated.resources.pattern
import curare.composeapp.generated.resources.pick_two_or_more_fields_at_least_one_of_which_an_entry_must_have
import curare.composeapp.generated.resources.picture_url
import curare.composeapp.generated.resources.placeholder
import curare.composeapp.generated.resources.propose_schema
import curare.composeapp.generated.resources.pubkeys_besides_the_group_that_may_suggest_to_this_list
import curare.composeapp.generated.resources.relay_url
import curare.composeapp.generated.resources.relays
import curare.composeapp.generated.resources.remove_field
import curare.composeapp.generated.resources.required
import curare.composeapp.generated.resources.say_who_may_suggest_to_this_list
import curare.composeapp.generated.resources.suggesters
import curare.composeapp.generated.resources.that_is_not_a_domain_name
import curare.composeapp.generated.resources.that_is_not_a_pubkey
import curare.composeapp.generated.resources.that_is_not_a_relay_address
import curare.composeapp.generated.resources.that_pubkey_is_already_a_suggester
import curare.composeapp.generated.resources.that_relay_is_already_in_this_list
import curare.composeapp.generated.resources.the_description_must_be_at_most_n_characters
import curare.composeapp.generated.resources.the_group_signs_a_schema_so_it_takes_a_quorum
import curare.composeapp.generated.resources.the_identifier_must_be_at_most_n_characters
import curare.composeapp.generated.resources.the_identifier_names_this_list_in_every_reply
import curare.composeapp.generated.resources.the_name_must_be_at_most_n_characters
import curare.composeapp.generated.resources.the_picture_must_be_an_https_url
import curare.composeapp.generated.resources.the_tag_s_third_element_which_tells_fields_sharing_a_tag_apart
import curare.composeapp.generated.resources.the_title_must_be_at_most_n_characters
import curare.composeapp.generated.resources.this_group_has_no_shared_key_to_sign_a_schema
import curare.composeapp.generated.resources.title
import curare.composeapp.generated.resources.type
import curare.composeapp.generated.resources.visibility
import curare.composeapp.generated.resources.visibility_closed
import curare.composeapp.generated.resources.visibility_closed_purpose
import curare.composeapp.generated.resources.visibility_private
import curare.composeapp.generated.resources.visibility_private_purpose
import curare.composeapp.generated.resources.visibility_public
import curare.composeapp.generated.resources.visibility_public_purpose
import curare.composeapp.generated.resources.what_an_entry_in_this_list_may_contain
import curare.composeapp.generated.resources.where_suggestions_to_this_list_are_published
import curare.composeapp.generated.resources.writes_to_tag
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The form for a list the group curates: what an entry may contain, who may
 * suggest one, and where suggestions go.
 *
 * One screen for the whole schema, because the schema is one event and one
 * signature. The identity -- identifier, title, name, description, visibility,
 * picture, domain -- is text fields like every other editor's. Under it are the
 * four things a schema has several of: fields, rules saying at least one of some
 * fields must be present, extra suggesters on a closed list, and relays. Each
 * is a list with a row per entry and a way to add one, and a field is edited on
 * a sheet of its own, since a field has a dozen settings and thirteen of them
 * inline would be a screen nobody could find anything on.
 *
 * **The button proposes rather than saves.** The schema goes out as one event
 * for the room's quorum to put its key to, and the screen hands over to the
 * signing session it opened. Nothing about the list has changed by the time this
 * screen closes -- and until a quorum signs, suggesters have nothing to reply to.
 *
 * **An edit keeps the identifier.** The identifier is the coordinate every
 * suggestion to the list replies to, so changing it would not edit the list but
 * start a second one and orphan the first's queue. The field is read-only on an
 * edit and says why; a new list is "Add schema" on the group's screen.
 */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun EditGroupCuratedSchemaScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    identifier: String?,
    initialEditGroupCuratedSchemaUIState: EditGroupCuratedSchemaUIState =
        EditGroupCuratedSchemaUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val editGroupCuratedSchemaViewModel: EditGroupCuratedSchemaViewModel = viewModel(
        factory = EditGroupCuratedSchemaViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            identifier = identifier,
            initialEditGroupCuratedSchemaUIState = initialEditGroupCuratedSchemaUIState,
            activeUserPublicKey = activeUserPublicKey,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Read out here rather than in a click handler: both are composable and an
    // onClick lambda is not. The scope is the caller's so a message survives this
    // screen being replaced by the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_this_schema)
    val nothingChanged = stringResource(Res.string.nothing_has_changed_to_propose)

    ScreenStateTransition(editGroupCuratedSchemaViewModel.editGroupCuratedSchemaUIState) { uiState ->
        when (val editGroupCuratedSchemaUIState = uiState) {
            is EditGroupCuratedSchemaUIState.Error -> {
                // Nothing to retry: the room and the identifier came from a
                // navigation argument, and reading them again with the same ones
                // fails the same way.
                ErrorState(message = editGroupCuratedSchemaUIState.message, onRetry = null)
            }

            is EditGroupCuratedSchemaUIState.Loaded -> {
                val existing = editGroupCuratedSchemaUIState.existing
                val schema = existing?.schema
                val canSign = editGroupCuratedSchemaUIState.canSign

                // Seeded from the schema the group already signed, so the form is
                // an edit of it. `rememberTextFieldState` saves what is typed, which
                // is what survives a rotation with the edits intact.
                val identifierFieldState = rememberTextFieldState(schema?.identifier ?: "")
                val titleFieldState = rememberTextFieldState(schema?.title ?: "")
                val nameFieldState = rememberTextFieldState(schema?.name ?: "")
                val descriptionFieldState = rememberTextFieldState(schema?.description ?: "")
                val pictureFieldState = rememberTextFieldState(schema?.picture ?: "")
                val domainFieldState = rememberTextFieldState(schema?.domain ?: "")
                val relayFieldState = rememberTextFieldState()
                val suggesterFieldState = rememberTextFieldState()

                // The lists come from the state rather than from whatever loaded
                // it, so a screen handed a loaded state -- a preview, a layout
                // test -- fills in the same as the app does. Seeding runs once.
                LaunchedEffect(editGroupCuratedSchemaUIState) {
                    editGroupCuratedSchemaViewModel.seedWorkingCopy(editGroupCuratedSchemaUIState)
                }

                // A refusal is about what is in the field, so it goes the moment
                // the field changes rather than sitting under a value that has
                // since been corrected.
                LaunchedEffect(relayFieldState) {
                    snapshotFlow { relayFieldState.text.toString() }
                        .collect { editGroupCuratedSchemaViewModel.clearAddRelayFailure() }
                }
                LaunchedEffect(suggesterFieldState) {
                    snapshotFlow { suggesterFieldState.text.toString() }
                        .collect { editGroupCuratedSchemaViewModel.clearAddSuggesterFailure() }
                }

                // M3 gives a FAB no `enabled`, so borrow the disabled colours every
                // other button in the app uses rather than inventing a shade here.
                val buttonColors = ButtonDefaults.buttonColors()

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                editGroupCuratedSchemaUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFloatingActionButton(
                                    modifier = if (canSign) {
                                        Modifier
                                    } else {
                                        // Looking unavailable is not being unavailable.
                                        Modifier.semantics { disabled() }
                                    },
                                    containerColor = if (canSign) {
                                        FloatingActionButtonDefaults.containerColor
                                    } else {
                                        buttonColors.disabledContainerColor
                                    },
                                    contentColor = if (canSign) {
                                        contentColorFor(FloatingActionButtonDefaults.containerColor)
                                    } else {
                                        buttonColors.disabledContentColor
                                    },
                                    onClick = {
                                        if (!canSign) return@ExtendedFloatingActionButton

                                        editGroupCuratedSchemaViewModel.proposeSchema(
                                            localChatRoom =
                                                editGroupCuratedSchemaUIState.localChatRoom,
                                            existing = existing,
                                            identifier = identifierFieldState.text.toString(),
                                            title = titleFieldState.text.toString(),
                                            name = nameFieldState.text.toString(),
                                            description = descriptionFieldState.text.toString(),
                                            picture = pictureFieldState.text.toString(),
                                            domain = domainFieldState.text.toString(),
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back
                                                // to the group. The schema has not
                                                // changed yet -- it changes when a
                                                // quorum signs -- so landing on a
                                                // group still showing the old one
                                                // would read as a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            // The reasons are drawn on the form
                                            // itself, where the fields they are
                                            // about are; a snackbar would name a
                                            // problem and scroll away from it.
                                            onInvalid = {},
                                            // Both stay on the form with the edits
                                            // still in it. One is a question and
                                            // one is a failure, and neither is a
                                            // reason to throw away the typing.
                                            onNothingToPropose = { notify(nothingChanged) },
                                            onFailure = { notify(couldNotPropose) }
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.Draw, contentDescription = "Propose schema")
                                    Text(stringResource(Res.string.propose_schema))
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    // One lazy list for the whole form rather than a scrolling
                    // column, because the form has four lists in it and a lazy
                    // list cannot sit inside a scrolling column. The text fields
                    // are declared above so that scrolling one off the screen does
                    // not lose what was typed into it.
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MaterialTheme.spacing.screenMargin,
                            vertical = MaterialTheme.spacing.itemGap
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            Text(
                                text = stringResource(Res.string.what_an_entry_in_this_list_may_contain),
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center
                            )
                        }

                        item {
                            Text(
                                text = stringResource(
                                    Res.string.the_group_signs_a_schema_so_it_takes_a_quorum
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (!canSign) {
                            item {
                                Text(
                                    text = stringResource(
                                        Res.string.this_group_has_no_shared_key_to_sign_a_schema
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Named rather than silent, and all of them at once: a
                        // button that does nothing for a schema it refuses is
                        // indistinguishable from a missed tap, and one reason per
                        // press would be a conversation.
                        if (editGroupCuratedSchemaViewModel.problems.isNotEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(
                                        MaterialTheme.spacing.relatedGap
                                    )
                                ) {
                                    editGroupCuratedSchemaViewModel.problems.forEach { problem ->
                                        Text(
                                            text = problem.message(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            SchemaTextField(
                                state = identifierFieldState,
                                icon = Icons.Default.Tag,
                                iconDescription = "Identifier of the list",
                                label = stringResource(Res.string.identifier),
                                placeholder = stringResource(Res.string.eg_bitcoin_mov),
                                // Locked on an edit: see the screen's own note.
                                readOnly = existing != null,
                                supportingText = if (existing != null) {
                                    stringResource(Res.string.the_identifier_names_this_list_in_every_reply)
                                } else {
                                    null
                                },
                                isError = editGroupCuratedSchemaViewModel.problems.containsAny(
                                    CuratedSchemaProblem.IdentifierMissing,
                                    CuratedSchemaProblem.IdentifierTooLong
                                )
                            )
                        }

                        item {
                            SchemaTextField(
                                state = titleFieldState,
                                icon = Icons.Default.Title,
                                iconDescription = "Title of the schema",
                                label = stringResource(Res.string.title),
                                placeholder = stringResource(Res.string.eg_bitcoin_mov_suggestion),
                                isError = editGroupCuratedSchemaViewModel.problems.containsAny(
                                    CuratedSchemaProblem.TitleMissing,
                                    CuratedSchemaProblem.TitleTooLong
                                )
                            )
                        }

                        item {
                            SchemaTextField(
                                state = nameFieldState,
                                icon = Icons.AutoMirrored.Filled.Label,
                                iconDescription = "Name of the list",
                                label = stringResource(Res.string.name),
                                placeholder = stringResource(Res.string.eg_bitcoin_mov),
                                isError = editGroupCuratedSchemaViewModel.problems.containsAny(
                                    CuratedSchemaProblem.NameMissing,
                                    CuratedSchemaProblem.NameTooLong
                                )
                            )
                        }

                        item {
                            SchemaTextField(
                                state = descriptionFieldState,
                                icon = Icons.AutoMirrored.Filled.Notes,
                                iconDescription = "What the list is for",
                                label = stringResource(Res.string.description),
                                placeholder = stringResource(
                                    Res.string.eg_fields_for_a_bitcoin_mov_entry
                                ),
                                // The one identity field with prose in it, and the
                                // only one a single line would truncate while it
                                // was being typed.
                                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                                isError = editGroupCuratedSchemaViewModel.problems.containsAny(
                                    CuratedSchemaProblem.DescriptionMissing,
                                    CuratedSchemaProblem.DescriptionTooLong
                                )
                            )
                        }

                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(
                                    MaterialTheme.spacing.relatedGap
                                )
                            ) {
                                SectionHeading(stringResource(Res.string.visibility))

                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    CuratedVisibility.entries.forEachIndexed { index, option ->
                                        SegmentedButton(
                                            selected = option == editGroupCuratedSchemaViewModel.visibility,
                                            onClick = {
                                                editGroupCuratedSchemaViewModel.selectVisibility(option)
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = CuratedVisibility.entries.size
                                            ),
                                            label = { Text(stringResource(option.label())) }
                                        )
                                    }
                                }

                                // What the chosen word actually decides. Three
                                // words is three questions nobody can be expected
                                // to hold apart from the words alone.
                                Text(
                                    text = stringResource(
                                        editGroupCuratedSchemaViewModel.visibility.purpose()
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        item {
                            SchemaTextField(
                                state = pictureFieldState,
                                icon = Icons.Default.Image,
                                iconDescription = "Picture of the list",
                                label = stringResource(Res.string.picture_url),
                                placeholder = stringResource(Res.string.eg_https_example_com_group_png),
                                isError = editGroupCuratedSchemaViewModel.problems.containsAny(CuratedSchemaProblem.PictureNotHttps)
                            )
                        }

                        item {
                            SchemaTextField(
                                state = domainFieldState,
                                icon = Icons.Default.Language,
                                iconDescription = "Domain of the list",
                                label = stringResource(Res.string.domain),
                                placeholder = stringResource(Res.string.eg_example_com),
                                supportingText = stringResource(
                                    Res.string.a_domain_replaces_the_name_wherever_the_list_is_shown
                                ),
                                isError = editGroupCuratedSchemaViewModel.problems.containsAny(CuratedSchemaProblem.DomainInvalid)
                            )
                        }

                        item {
                            SectionHeading(stringResource(Res.string.fields))
                        }

                        itemsIndexed(editGroupCuratedSchemaViewModel.fields) { index, field ->
                            SchemaFieldRow(
                                field = field,
                                isMandatory = editGroupCuratedSchemaViewModel.isMandatory(field),
                                onOpen = { editGroupCuratedSchemaViewModel.startEditingField(index) }
                            )
                        }

                        item {
                            TextButton(onClick = { editGroupCuratedSchemaViewModel.startAddingField() }) {
                                Icon(Icons.Default.Add, contentDescription = Decorative)

                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

                                Text(stringResource(Res.string.add_field))
                            }
                        }

                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(
                                    MaterialTheme.spacing.relatedGap
                                )
                            ) {
                                SectionHeading(stringResource(Res.string.at_least_one_of))

                                Text(
                                    text = stringResource(
                                        Res.string.pick_two_or_more_fields_at_least_one_of_which_an_entry_must_have
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        itemsIndexed(editGroupCuratedSchemaViewModel.requireAny) { index, group ->
                            RequireAnyRow(
                                fieldNames = editGroupCuratedSchemaViewModel.fields.map { it.name },
                                selected = group,
                                onToggle = { fieldName ->
                                    editGroupCuratedSchemaViewModel.toggleRequireAny(index, fieldName)
                                },
                                onRemove = { editGroupCuratedSchemaViewModel.removeRequireAnyGroup(index) }
                            )
                        }

                        item {
                            TextButton(
                                onClick = { editGroupCuratedSchemaViewModel.addRequireAnyGroup() }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = Decorative)

                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

                                Text(stringResource(Res.string.add_rule))
                            }
                        }

                        // Only where the visibility gives them something to be: on
                        // a public list anyone may suggest, and a list of people
                        // who may would say the opposite of what the word does.
                        if (editGroupCuratedSchemaViewModel.visibility != CuratedVisibility.Public) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(
                                        MaterialTheme.spacing.relatedGap
                                    )
                                ) {
                                    SectionHeading(stringResource(Res.string.suggesters))

                                    Text(
                                        text = stringResource(
                                            Res.string.pubkeys_besides_the_group_that_may_suggest_to_this_list
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            items(editGroupCuratedSchemaViewModel.suggesters) { pubkey ->
                                RemovableRow(
                                    // The npub, because that is the form a member
                                    // pasted it in and the form they would compare
                                    // it against; whole and monospaced, so it can be.
                                    text = pubkey.hexToNpubHrp(),
                                    isMonospaced = true,
                                    removeDescription = "Remove suggester",
                                    onRemove = { editGroupCuratedSchemaViewModel.removeSuggester(pubkey) }
                                )
                            }

                            item {
                                AddRow(
                                    state = suggesterFieldState,
                                    icon = Icons.Default.PersonAdd,
                                    label = stringResource(Res.string.npub_or_hex_pubkey),
                                    placeholder = null,
                                    addDescription = "Add suggester",
                                    failure = when (editGroupCuratedSchemaViewModel.addSuggesterFailure) {
                                        AddSuggesterFailure.NotAPubkey ->
                                            stringResource(Res.string.that_is_not_a_pubkey)
                                        AddSuggesterFailure.AlreadyListed ->
                                            stringResource(Res.string.that_pubkey_is_already_a_suggester)
                                        null -> null
                                    },
                                    onAdd = { input -> editGroupCuratedSchemaViewModel.addSuggester(input) }
                                )
                            }
                        }

                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(
                                    MaterialTheme.spacing.relatedGap
                                )
                            ) {
                                SectionHeading(stringResource(Res.string.relays))

                                Text(
                                    text = stringResource(
                                        Res.string.where_suggestions_to_this_list_are_published
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (editGroupCuratedSchemaViewModel.relays.isEmpty()) {
                                    Text(
                                        text = stringResource(
                                            Res.string.no_relays_named_so_a_client_will_pick_its_own
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        items(editGroupCuratedSchemaViewModel.relays) { url ->
                            RemovableRow(
                                text = url,
                                isMonospaced = false,
                                removeDescription = "Remove relay",
                                onRemove = { editGroupCuratedSchemaViewModel.removeRelay(url) }
                            )
                        }

                        item {
                            AddRow(
                                state = relayFieldState,
                                icon = Icons.Default.Dns,
                                label = stringResource(Res.string.relay_url),
                                placeholder = stringResource(Res.string.eg_wss_relay_example_com),
                                addDescription = "Add relay",
                                failure = when (editGroupCuratedSchemaViewModel.addRelayFailure) {
                                    AddRelayFailure.NotARelay ->
                                        stringResource(Res.string.that_is_not_a_relay_address)
                                    AddRelayFailure.AlreadyListed ->
                                        stringResource(Res.string.that_relay_is_already_in_this_list)
                                    null -> null
                                },
                                onAdd = { input -> editGroupCuratedSchemaViewModel.addRelay(input) }
                            )
                        }
                    }
                }

                editGroupCuratedSchemaViewModel.editingField?.let { edit ->
                    FieldEditorSheet(
                        edit = edit,
                        isMandatory = editGroupCuratedSchemaViewModel.isMandatory(edit.field),
                        onCommit = { field -> editGroupCuratedSchemaViewModel.commitField(field) },
                        onRemove = { edit.index?.let(editGroupCuratedSchemaViewModel::removeField) },
                        onDismiss = { editGroupCuratedSchemaViewModel.dismissFieldEditor() }
                    )
                }
            }

            EditGroupCuratedSchemaUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                    Text(
                        text = stringResource(
                            if (identifier == null) Res.string.add_schema else Res.string.edit_schema
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(fillScreen = false)
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialEditGroupCuratedSchemaUIState == EditGroupCuratedSchemaUIState.Loading) {
            editGroupCuratedSchemaViewModel.initiateEditGroupCuratedSchema()
        }
    }
}

/** The word for who may suggest, for the group's screen and the editor alike. */
internal fun CuratedVisibility.label(): StringResource = when (this) {
    CuratedVisibility.Public -> Res.string.visibility_public
    CuratedVisibility.Closed -> Res.string.visibility_closed
    CuratedVisibility.Private -> Res.string.visibility_private
}

/** The sentence saying what the word decides. */
private fun CuratedVisibility.purpose(): StringResource = when (this) {
    CuratedVisibility.Public -> Res.string.visibility_public_purpose
    CuratedVisibility.Closed -> Res.string.visibility_closed_purpose
    CuratedVisibility.Private -> Res.string.visibility_private_purpose
}

/**
 * The name of a field type, for the chips and the rows.
 *
 * A `when` rather than a field on each entry so that the enum stays what it is
 * -- the NIP's nine words and their one rule each -- with no catalogue behind
 * it, and so that a tenth type is a compile error here rather than a chip with
 * no name.
 */
private fun CuratedFieldType.label(): StringResource = when (this) {
    CuratedFieldType.Text -> Res.string.field_type_text
    CuratedFieldType.LongText -> Res.string.field_type_longtext
    CuratedFieldType.Token -> Res.string.field_type_token
    CuratedFieldType.Url -> Res.string.field_type_url
    CuratedFieldType.Image -> Res.string.field_type_image
    CuratedFieldType.Enum -> Res.string.field_type_enum
    CuratedFieldType.Year -> Res.string.field_type_year
    CuratedFieldType.Duration -> Res.string.field_type_duration
    CuratedFieldType.Number -> Res.string.field_type_number
}

/**
 * Why a schema cannot be proposed, said to the member.
 *
 * The caps are quoted from `CuratedSchemaEvent` rather than repeated, so the
 * sentence and the check cannot disagree about the number.
 */
@Composable
internal fun CuratedSchemaProblem.message(): String = when (this) {
    CuratedSchemaProblem.IdentifierMissing -> stringResource(Res.string.an_identifier_is_required)
    CuratedSchemaProblem.IdentifierTooLong -> stringResource(
        Res.string.the_identifier_must_be_at_most_n_characters,
        CuratedSchemaEvent.MAX_IDENTIFIER.toString()
    )
    CuratedSchemaProblem.TitleMissing -> stringResource(Res.string.a_title_is_required)
    CuratedSchemaProblem.TitleTooLong -> stringResource(
        Res.string.the_title_must_be_at_most_n_characters,
        CuratedSchemaEvent.MAX_TITLE.toString()
    )
    CuratedSchemaProblem.NameMissing -> stringResource(Res.string.a_name_is_required)
    CuratedSchemaProblem.NameTooLong -> stringResource(
        Res.string.the_name_must_be_at_most_n_characters,
        CuratedSchemaEvent.MAX_NAME.toString()
    )
    CuratedSchemaProblem.DescriptionMissing -> stringResource(Res.string.a_description_is_required)
    CuratedSchemaProblem.DescriptionTooLong -> stringResource(
        Res.string.the_description_must_be_at_most_n_characters,
        CuratedSchemaEvent.MAX_DESCRIPTION.toString()
    )
    CuratedSchemaProblem.VisibilityMissing -> stringResource(Res.string.say_who_may_suggest_to_this_list)
    CuratedSchemaProblem.PictureNotHttps -> stringResource(Res.string.the_picture_must_be_an_https_url)
    CuratedSchemaProblem.DomainInvalid -> stringResource(Res.string.that_is_not_a_domain_name)
    CuratedSchemaProblem.NoFields -> stringResource(Res.string.a_schema_needs_at_least_one_field)
    CuratedSchemaProblem.RelayInvalid -> stringResource(Res.string.one_of_the_relays_is_not_a_relay_address)
}

/** Whether any of [problems] is about the field asking, for its error state. */
private fun List<CuratedSchemaProblem>.containsAny(vararg problems: CuratedSchemaProblem): Boolean =
    problems.any { it in this }

/** A heading over one part of the form, at the leading edge like the rows under it. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = MaterialTheme.typography.titleSmall
    )
}

/**
 * One text field of the form, in the shape the app's other forms use.
 *
 * The borderless outlined field over the bottom bar's tint is four lines of
 * colours apiece, and this screen has more of them than any other -- so the
 * function, and the three things this one adds over `NostrProfileField`: a
 * read-only state for the locked identifier, an error state for a field a
 * problem is about, and a line of supporting text for a field that needs one.
 */
@Composable
private fun SchemaTextField(
    state: TextFieldState,
    icon: ImageVector,
    iconDescription: String,
    label: String,
    placeholder: String,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    readOnly: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth().background(BottomAppBarDefaults.containerColor),
        state = state,
        lineLimits = lineLimits,
        readOnly = readOnly,
        isError = isError,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent
        ),
        leadingIcon = {
            Icon(icon, contentDescription = iconDescription)
        },
        label = {
            Text(text = label)
        },
        placeholder = {
            Text(text = placeholder)
        },
        supportingText = supportingText?.let { { Text(text = it) } }
    )
}

/**
 * One field of the schema, as a row that opens the sheet.
 *
 * The label is what a suggester will see over the input, so it is the headline;
 * the name, type and requirement are what the schema says about it. A
 * mandatory field is marked as required whatever its own flag says, since that
 * is what will be signed.
 */
@Composable
private fun SchemaFieldRow(
    field: CuratedField,
    isMandatory: Boolean,
    onOpen: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        ListItem(
            leadingContent = {
                Icon(Icons.Default.Ballot, contentDescription = Decorative)
            },
            headlineContent = {
                Text(
                    text = field.labelOrName(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Text(
                    text = listOfNotNull(
                        field.name,
                        stringResource(field.type.label()),
                        stringResource(
                            if (field.isRequired || isMandatory) Res.string.required else Res.string.optional
                        ),
                        stringResource(Res.string.derived).takeIf { field.config.derived == true }
                    ).joinToString(" · "),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

/**
 * One `require-any` rule: a chip per field, selected for the ones in the rule.
 *
 * Chips over a picker because the whole point of a rule is which fields are in
 * it, and a chip row shows that at a glance where a picker would show a count.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequireAnyRow(
    fieldNames: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.compactPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
            ) {
                fieldNames.forEach { fieldName ->
                    FilterChip(
                        selected = fieldName in selected,
                        onClick = { onToggle(fieldName) },
                        label = { Text(fieldName) }
                    )
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove rule",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** One entry of a plain list -- a relay, a suggester -- with the way to drop it. */
@Composable
private fun RemovableRow(
    text: String,
    isMonospaced: Boolean,
    removeDescription: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (isMonospaced) FontFamily.Monospace else null
        )

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = removeDescription,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * A field and a button that adds what is in it to a list, and the reason when
 * it would not.
 *
 * [onAdd] answers whether the row appeared, which is the cue to clear the field
 * -- a refused value stays where it can be corrected.
 */
@Composable
private fun AddRow(
    state: TextFieldState,
    icon: ImageVector,
    label: String,
    placeholder: String?,
    addDescription: String,
    failure: String?,
    onAdd: (String) -> Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f).background(BottomAppBarDefaults.containerColor),
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent
                ),
                leadingIcon = {
                    Icon(icon, contentDescription = Decorative)
                },
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                isError = failure != null
            )

            Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

            IconButton(
                onClick = {
                    if (onAdd(state.text.toString())) {
                        state.clearText()
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = addDescription)
            }
        }

        failure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * The sheet a field is edited on.
 *
 * Split into the sheet and its content the way `GroupKeyStateSheet` is, so the
 * content can be rendered and measured on its own -- a bottom sheet is a popup
 * with its own window, which a layout test cannot reach into.
 */
@Composable
private fun FieldEditorSheet(
    edit: FieldEdit,
    isMandatory: Boolean,
    onCommit: (CuratedField) -> FieldProblem?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        FieldEditorSheetContent(
            edit = edit,
            isMandatory = isMandatory,
            onCommit = onCommit,
            onRemove = onRemove
        )
    }
}

/**
 * Everything a field can say about itself, and a button that puts it in the
 * schema.
 *
 * The positional parts of the tag first -- name, label, placeholder, type,
 * required -- because they are what a form needs; the config keys after, and
 * only the ones the chosen type gives a meaning to. A minimum on a text field or
 * options on a year would be written into the event and mean nothing, so they
 * are not offered and, when the type changes away from them, not kept.
 *
 * **A mandatory field cannot stop being one.** Its tag is read-only and its
 * requirement is on and disabled, because `CuratedSchema.normalized` would only
 * put a field writing to `d` or `title` back if this let it be taken away -- so
 * offering the change would offer something the event will not carry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FieldEditorSheetContent(
    edit: FieldEdit,
    isMandatory: Boolean,
    onCommit: (CuratedField) -> FieldProblem?,
    onRemove: () -> Unit
) {
    val field = edit.field

    val nameFieldState = rememberTextFieldState(field.name)
    val labelFieldState = rememberTextFieldState(field.label)
    val placeholderFieldState = rememberTextFieldState(field.placeholder)
    val tagFieldState = rememberTextFieldState(field.config.tag ?: "")
    val markerFieldState = rememberTextFieldState(field.config.marker ?: "")
    val maxFieldState = rememberTextFieldState(field.config.max?.toString() ?: "")
    val minFieldState = rememberTextFieldState(field.config.min?.toString() ?: "")
    val optionsFieldState = rememberTextFieldState(field.config.options?.joinToString("\n") ?: "")
    val patternFieldState = rememberTextFieldState(field.config.pattern ?: "")
    val hintFieldState = rememberTextFieldState(field.config.hint ?: "")

    var type by rememberSaveable { mutableStateOf(field.type) }
    var isRequired by rememberSaveable { mutableStateOf(field.isRequired || isMandatory) }
    var mayRepeat by rememberSaveable { mutableStateOf(field.config.repeat == true) }
    var isHttpsOnly by rememberSaveable { mutableStateOf(field.config.https == true) }
    var isDerived by rememberSaveable { mutableStateOf(field.config.derived == true) }

    var problem: FieldProblem? by remember { mutableStateOf(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        Text(
            text = stringResource(
                if (edit.index == null) Res.string.new_field else Res.string.edit_field
            ),
            style = MaterialTheme.typography.titleMedium
        )

        SchemaTextField(
            state = nameFieldState,
            icon = Icons.Default.Tag,
            iconDescription = "Name of the field",
            label = stringResource(Res.string.field_name),
            placeholder = stringResource(Res.string.eg_watchurl),
            isError = problem != null
        )

        // Named rather than silent, under the field it is about.
        problem?.let {
            Text(
                text = when (it) {
                    FieldProblem.NameMissing -> stringResource(Res.string.a_field_needs_a_name)
                    FieldProblem.NameHasSpaces -> stringResource(Res.string.a_field_name_is_one_word)
                    FieldProblem.NameTaken -> stringResource(Res.string.a_field_named_that_already_exists)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        SchemaTextField(
            state = labelFieldState,
            icon = Icons.AutoMirrored.Filled.Label,
            iconDescription = "Label over the input",
            label = stringResource(Res.string.label),
            placeholder = stringResource(Res.string.eg_watch_reference_url)
        )

        SchemaTextField(
            state = placeholderFieldState,
            icon = Icons.AutoMirrored.Filled.ShortText,
            iconDescription = "Example shown in an empty input",
            label = stringResource(Res.string.placeholder),
            placeholder = stringResource(Res.string.eg_https_youtube_com_watch)
        )

        Text(
            text = stringResource(Res.string.type),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            CuratedFieldType.entries.forEach { option ->
                FilterChip(
                    selected = option == type,
                    onClick = { type = option },
                    label = { Text(stringResource(option.label())) }
                )
            }
        }

        SwitchRow(
            label = stringResource(Res.string.an_entry_must_have_this_field),
            checked = isRequired,
            enabled = !isMandatory,
            onCheckedChange = { isRequired = it }
        )

        SchemaTextField(
            state = tagFieldState,
            icon = Icons.Default.Sell,
            iconDescription = "Tag the field writes to",
            label = stringResource(Res.string.writes_to_tag),
            placeholder = nameFieldState.text.toString().ifBlank { stringResource(Res.string.eg_watchurl) },
            readOnly = isMandatory,
            supportingText = stringResource(
                Res.string.defaults_to_the_field_name_content_means_the_body
            )
        )

        SchemaTextField(
            state = markerFieldState,
            icon = Icons.Default.Bookmark,
            iconDescription = "Marker on the tag",
            label = stringResource(Res.string.marker),
            placeholder = stringResource(Res.string.eg_watch),
            supportingText = stringResource(
                Res.string.the_tag_s_third_element_which_tells_fields_sharing_a_tag_apart
            )
        )

        SchemaTextField(
            state = maxFieldState,
            icon = Icons.Default.Numbers,
            iconDescription = "Maximum",
            label = stringResource(
                if (type.isNumeric) Res.string.maximum_value else Res.string.maximum_characters
            ),
            placeholder = stringResource(if (type.isNumeric) Res.string.eg_2100 else Res.string.eg_200)
        )

        if (type.isNumeric) {
            SchemaTextField(
                state = minFieldState,
                icon = Icons.Default.Numbers,
                iconDescription = "Minimum",
                label = stringResource(Res.string.minimum_value),
                placeholder = stringResource(Res.string.eg_1900)
            )
        }

        if (type == CuratedFieldType.Enum) {
            SchemaTextField(
                state = optionsFieldState,
                icon = Icons.Default.Checklist,
                iconDescription = "Allowed values",
                label = stringResource(Res.string.options),
                placeholder = stringResource(Res.string.eg_movie_documentary_short),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                supportingText = stringResource(Res.string.one_per_line_or_separated_by_commas)
            )
        }

        if (type == CuratedFieldType.Url) {
            SwitchRow(
                label = stringResource(Res.string.https_only),
                checked = isHttpsOnly,
                enabled = true,
                onCheckedChange = { isHttpsOnly = it }
            )
        }

        SwitchRow(
            label = stringResource(Res.string.may_appear_more_than_once),
            checked = mayRepeat,
            enabled = true,
            onCheckedChange = { mayRepeat = it }
        )

        SchemaTextField(
            state = patternFieldState,
            icon = Icons.AutoMirrored.Filled.Rule,
            iconDescription = "Pattern the value must match",
            label = stringResource(Res.string.pattern),
            placeholder = stringResource(Res.string.eg_a_za_z_2_8),
            supportingText = stringResource(
                Res.string.a_regular_expression_the_whole_value_must_match
            )
        )

        SwitchRow(
            label = stringResource(Res.string.filled_in_by_the_app_never_asked_for),
            checked = isDerived,
            enabled = true,
            onCheckedChange = { isDerived = it }
        )

        SchemaTextField(
            state = hintFieldState,
            icon = Icons.Default.Lightbulb,
            iconDescription = "Helper text",
            label = stringResource(Res.string.hint),
            placeholder = stringResource(Res.string.helper_text_under_the_input)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isMandatory && edit.index != null) {
                TextButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(Res.string.remove_field))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    problem = onCommit(
                        CuratedField(
                            name = nameFieldState.text.toString().trim(),
                            type = type,
                            isRequired = isRequired || isMandatory,
                            placeholder = placeholderFieldState.text.toString().trim(),
                            label = labelFieldState.text.toString().trim(),
                            config = field.config.copy(
                                // Pinned for a mandatory field, so that renaming it
                                // cannot move it off the tag that makes it one.
                                tag = if (isMandatory) {
                                    field.tag()
                                } else {
                                    tagFieldState.text.toString().trim().ifEmpty { null }
                                },
                                marker = markerFieldState.text.toString().trim().ifEmpty { null },
                                max = maxFieldState.text.toString().trim().toLongOrNull(),
                                min = if (type.isNumeric) {
                                    minFieldState.text.toString().trim().toLongOrNull()
                                } else {
                                    null
                                },
                                options = if (type == CuratedFieldType.Enum) {
                                    optionsFieldState.text.toString()
                                        .split('\n', ',')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .ifEmpty { null }
                                } else {
                                    null
                                },
                                https = if (type == CuratedFieldType.Url && isHttpsOnly) true else null,
                                repeat = if (mayRepeat) true else null,
                                pattern = patternFieldState.text.toString().trim().ifEmpty { null },
                                derived = if (isDerived) true else null,
                                hint = hintFieldState.text.toString().trim().ifEmpty { null },
                            )
                        )
                    )
                }
            ) {
                Text(stringResource(Res.string.done))
            }
        }
    }
}

/**
 * A labelled switch, the label taking the width and the switch its own target.
 *
 * The label is also the switch's description, so a screen reader announces what
 * is being turned on rather than "switch, off" beside a sentence it has already
 * read past.
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )

        Switch(
            modifier = Modifier.semantics { contentDescription = label },
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@ConformancePreviews
@Composable
private fun EditGroupCuratedSchemaScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            EditGroupCuratedSchemaScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                identifier = null,
                initialEditGroupCuratedSchemaUIState = EditGroupCuratedSchemaUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "publicKey",
                            userPublicKey = "",
                            subject = "Translation room",
                            description = "A group translating hard books.",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        ),
                    ),
                    // A new schema, so the editor seeds the two fields every list
                    // has and the preview shows what a group actually starts from
                    // rather than an empty form.
                    defaultRelays = listOf("wss://relay.example.com"),
                    // A group that can sign, so the form renders in the state a
                    // member actually meets it in rather than greyed out.
                    canSign = true
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {},
                onNavigateBack = {},
            )
        }
    }
}
