package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.ask_again
import curare.composeapp.generated.resources.asking_the_list_s_relays
import curare.composeapp.generated.resources.copied_the_suggestion
import curare.composeapp.generated.resources.copy_the_suggestion
import curare.composeapp.generated.resources.curated
import curare.composeapp.generated.resources.everything_anyone_has_suggested_to_this_list
import curare.composeapp.generated.resources.n_curated
import curare.composeapp.generated.resources.n_suggestions
import curare.composeapp.generated.resources.nobody_has_suggested_anything_to_this_list_yet
import curare.composeapp.generated.resources.one_suggestion
import curare.composeapp.generated.resources.read_from_relays
import curare.composeapp.generated.resources.suggested_by_on
import curare.composeapp.generated.resources.suggestions
import curare.composeapp.generated.resources.taken_up_by_the_group_into_the_list
import curare.composeapp.generated.resources.the_signed_event
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.toFormattedTimeAndDateString
import to.curare.compose.nostr.CuratedSuggestion
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedFieldConfig
import to.curare.compose.nostr.curated.CuratedFieldType
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.EmptyState
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import to.curare.compose.ui.composable.widgets.profile.ProfileAvatar
import to.curare.compose.ui.composable.widgets.rememberNotifier
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.CuratedSuggestionListViewModel
import to.curare.compose.ui.view.state.CuratedSuggestionListUIState
import kotlin.time.Instant

/**
 * Everything anyone has suggested to one list the group curates.
 *
 * The group's screen shows the schema: what the list asks for. This is what it
 * has been answered with -- one row per suggestion, newest first, whoever
 * signed it -- which is the view a member wants before the group takes
 * anything up: to see what is waiting, and what has already been taken.
 *
 * Read from the relays the schema names, and the header says which, because a
 * queue read from the wrong relays and a list nobody has written to look the
 * same from here. A suggestion that does not satisfy the schema is not on this
 * screen at all; see `CuratedSuggestion`.
 *
 * A row opens a sheet with the whole entry, field by field, and the event it
 * came as -- the thing a member can hand to somebody else to check, and the
 * thing a canonical entry is made from.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CuratedSuggestionListScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    identifier: String,
    initialCuratedSuggestionListUIState: CuratedSuggestionListUIState =
        CuratedSuggestionListUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit,
) {
    val curatedSuggestionListViewModel: CuratedSuggestionListViewModel = viewModel(
        factory = CuratedSuggestionListViewModel.factory(
            chatRoomId = chatRoomId,
            activeUserPublicKey = activeUserPublicKey,
            relayHint = relayHint,
            identifier = identifier,
            initialCuratedSuggestionListUIState = initialCuratedSuggestionListUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialCuratedSuggestionListUIState == CuratedSuggestionListUIState.Loading) {
            curatedSuggestionListViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.suggestions),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(curatedSuggestionListViewModel.curatedSuggestionListUIState) { uiState ->
            when (val state = uiState) {
                is CuratedSuggestionListUIState.Error -> {
                    // Nothing to retry: the room and the identifier came from a
                    // navigation argument, and reading them again with the same
                    // ones fails the same way.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                CuratedSuggestionListUIState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(padding).padding(MaterialTheme.spacing.screenMargin),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                    ) {
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                        LoadingDataIndicator(fillScreen = false)
                    }
                }

                is CuratedSuggestionListUIState.Loaded -> {
                    // The id rather than the row, so the sheet follows an edit
                    // that replaces the row under it, and survives a rotation.
                    var openSuggestionId by rememberSaveable { mutableStateOf<String?>(null) }

                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.space250),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        item {
                            CuratedQueueHeader(state = state)
                        }

                        if (state.suggestions.isEmpty()) {
                            item {
                                if (state.isAsking) {
                                    LoadingDataIndicator(
                                        fillScreen = false,
                                        text = stringResource(Res.string.asking_the_list_s_relays)
                                    )
                                } else {
                                    // Empty is a fact about the relays that were
                                    // asked, and asking again is the one thing a
                                    // member can do about it from here.
                                    EmptyState(
                                        message = stringResource(
                                            Res.string.nobody_has_suggested_anything_to_this_list_yet
                                        ),
                                        action = {
                                            TextButton(
                                                onClick = { curatedSuggestionListViewModel.askAgain() }
                                            ) {
                                                Text(stringResource(Res.string.ask_again))
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            items(
                                items = state.suggestions,
                                key = { suggestion -> suggestion.id }
                            ) { suggestion ->
                                CuratedSuggestionCard(
                                    suggestion = suggestion,
                                    schema = state.schema.schema,
                                    onOpen = { openSuggestionId = suggestion.id }
                                )
                            }
                        }
                    }

                    openSuggestionId
                        ?.let { id -> state.suggestions.firstOrNull { it.id == id } }
                        ?.let { suggestion ->
                            CuratedSuggestionSheet(
                                suggestion = suggestion,
                                schema = state.schema.schema,
                                onDismiss = { openSuggestionId = null }
                            )
                        }
                }
            }
        }
    }
}

/**
 * What the queue is, in numbers, and where it was read from.
 *
 * The list is named the way its card on the group's screen names it -- the
 * domain where it claims one, else the name -- so a member arriving from that
 * card finds the same word. The counts are the reference client's line: how
 * many are waiting, and how many of those the group has already taken up.
 */
@Composable
private fun CuratedQueueHeader(state: CuratedSuggestionListUIState.Loaded) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
        Text(
            text = state.schema.schema.displayName(),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = stringResource(Res.string.everything_anyone_has_suggested_to_this_list),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = listOfNotNull(
                when (state.suggestions.size) {
                    1 -> stringResource(Res.string.one_suggestion)
                    else -> stringResource(Res.string.n_suggestions, state.suggestions.size)
                },
                state.curatedCount.takeIf { it > 0 }?.let { stringResource(Res.string.n_curated, it) }
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium
        )

        if (state.relays.isNotEmpty()) {
            Text(
                text = stringResource(
                    Res.string.read_from_relays,
                    state.relays.joinToString { relay ->
                        RelayUrlNormalizer.normalizeOrNull(relay)?.displayUrl() ?: relay
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One suggestion, as a row somebody is scanning.
 *
 * The title, because every list has one; a glance at the fields that say what
 * the entry *is* -- the type, the year, the director -- and not the ones that
 * say where to find it, since a URL on a row is a line of noise and the sheet
 * has them; the body where the list has one; and who suggested it, when. The
 * avatar is the suggester's, tinted from their key like every avatar here, so
 * two suggestions by one stranger read as one stranger.
 *
 * "Curated" on the row is the one thing on it a member might act on: it says
 * the group already took this up, and re-curating it would revise the entry
 * rather than add one.
 */
@Composable
private fun CuratedSuggestionCard(
    suggestion: CuratedSuggestion,
    schema: CuratedSchema,
    onOpen: () -> Unit
) {
    val entry = suggestion.entry

    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        ListItem(
            leadingContent = {
                ProfileAvatar(publicKey = entry.pubKey, profile = suggestion.suggester)
            },
            trailingContent = if (suggestion.isCurated) {
                { CuratedMark() }
            } else {
                null
            },
            headlineContent = {
                Text(
                    text = entry.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    glanceOf(entry, schema)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    bodyOf(entry, schema)?.let {
                        Text(
                            text = it,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = stringResource(
                            Res.string.suggested_by_on,
                            suggestion.suggesterName(),
                            suggestion.suggestedAt.toFormattedTimeAndDateString()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@Composable
private fun CuratedMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The word beside it says it, so the icon says nothing twice.
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = Decorative,
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))

        Text(
            text = stringResource(Res.string.curated),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The fields worth a glance on a row, as `Label: value`, or null when the entry
 * has none of them.
 *
 * The fields a form prompts for, less the title (the headline), the body (its
 * own line) and anything that is a link (the sheet). Labelled because a bare
 * "2014 · en" says nothing to somebody who does not know the list's fields,
 * and this screen is driven by a schema it has never seen before.
 */
private fun glanceOf(entry: CuratedEntry, schema: CuratedSchema): String? =
    schema.formFields()
        .filterNot { it.tag() == TITLE_TAG || it.tag() == CuratedFieldConfig.CONTENT_TAG }
        .filterNot { it.type == CuratedFieldType.Url || it.type == CuratedFieldType.Image }
        .mapNotNull { field ->
            entry.values[field.name]?.let { values -> "${field.labelOrName()}: ${values.joinToString()}" }
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/** The value of the field that writes to the body, or null. */
private fun bodyOf(entry: CuratedEntry, schema: CuratedSchema): String? =
    schema.fields.firstOrNull { it.tag() == CuratedFieldConfig.CONTENT_TAG }?.let { entry.value(it.name) }

private const val TITLE_TAG = "title"

/**
 * The whole of one suggestion, and a way to take it somewhere else.
 *
 * Every field the entry has a value for, labelled as the schema labels it, so
 * the sheet reads as the form would have -- and then the event itself, which
 * is the only part that can be checked, and the part a canonical entry is
 * built from. Byte for byte, the way the key state sheet shows the group's own
 * event, and for the same reason: a prettier rendering is a different string
 * to the one whose id was hashed.
 */
@Composable
private fun CuratedSuggestionSheet(
    suggestion: CuratedSuggestion,
    schema: CuratedSchema,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismiss = onDismiss) {
        CuratedSuggestionSheetContent(suggestion = suggestion, schema = schema)
    }
}

/**
 * The sheet's body, apart from the sheet. Split out so it can be rendered and
 * measured on its own, since a bottom sheet is a popup a layout test cannot
 * reach into.
 */
@Composable
internal fun CuratedSuggestionSheetContent(
    suggestion: CuratedSuggestion,
    schema: CuratedSchema
) {
    val clipboardManager = LocalClipboardManager.current

    // Read out here rather than in the click handler: both of these are
    // composable, and an onClick lambda is not.
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied_the_suggestion)

    val entry = suggestion.entry

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        Text(
            text = stringResource(
                Res.string.suggested_by_on,
                suggestion.suggesterName(),
                suggestion.suggestedAt.toFormattedTimeAndDateString()
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (suggestion.isCurated) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = Decorative,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))

                Text(
                    text = stringResource(Res.string.taken_up_by_the_group_into_the_list),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

        // In the schema's order, which is the order the form asked in, and
        // only the fields the entry answered: a form's empty inputs are not
        // worth a line each here. The title is the heading and is not said twice.
        schema.fields.filterNot { it.tag() == TITLE_TAG }.forEach { field ->
            entry.values[field.name]?.let { values ->
                CuratedSuggestionField(field = field, values = values)
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

        Text(
            text = stringResource(Res.string.the_signed_event),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        val json = suggestion.event.toEvent().toJson()

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(MaterialTheme.spacing.containerPadding)
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))

        FilledTonalButton(
            onClick = {
                clipboardManager.setText(AnnotatedString(json))
                notify(copied)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
            Text(text = stringResource(Res.string.copy_the_suggestion))
        }
    }
}

/**
 * One field of an entry: the schema's label over the value, or the values one
 * under another for a field that repeats. Monospaced where the value is a
 * thing rather than prose -- a token, a link -- because those are compared and
 * copied rather than read.
 */
@Composable
private fun CuratedSuggestionField(field: CuratedField, values: List<String>) {
    val isMonospaced = when (field.type) {
        CuratedFieldType.Token, CuratedFieldType.Url, CuratedFieldType.Image -> true
        else -> false
    }

    Text(
        text = field.labelOrName(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = values.joinToString("\n"),
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (isMonospaced) FontFamily.Monospace else null,
        // Stated rather than inherited: the label above is deliberately quieter,
        // and the value is the thing that was come for.
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))
}

@ConformancePreviews
@Composable
private fun CuratedSuggestionListScreenPreview() {
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
                CuratedField("type", CuratedFieldType.Enum, isRequired = true, label = "Type"),
                CuratedField(
                    "description",
                    CuratedFieldType.LongText,
                    isRequired = false,
                    label = "Why",
                    config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG)
                ),
            ),
            relays = listOf("wss://relay.one.example")
        )
    )

    fun suggestion(title: String, createdAt: Long, isCurated: Boolean) = CuratedSuggestion(
        entry = CuratedEntry(
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            id = createdAt.toString().padStart(64, 'a'),
            pubKey = suggester,
            createdAt = createdAt,
            identifier = title.lowercase().replace(' ', '-'),
            title = title,
            values = mapOf(
                "title" to listOf(title),
                "year" to listOf("2014"),
                "type" to listOf("documentary"),
                "description" to listOf("Follows the early Bitcoin community from 2011 onward."),
            )
        ),
        event = NostrEvent(
            id = createdAt.toString().padStart(64, 'a'),
            pubKey = suggester,
            kind = CuratedSchemaEvent.SUGGESTION_KIND,
            tags = emptyArray(),
            content = "",
            sig = "b".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt)
        ),
        suggester = null,
        isCurated = isCurated
    )

    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            CuratedSuggestionListScreen(
                activeUserPublicKey = "u".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                identifier = "films",
                initialCuratedSuggestionListUIState = CuratedSuggestionListUIState.Loaded(
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
                    relays = listOf("wss://relay.one.example"),
                    suggestions = listOf(
                        suggestion("The Rise and Rise of Bitcoin", 1_700_000_900, isCurated = true),
                        suggestion("Magic Money", 1_700_000_000, isCurated = false),
                    )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onNavigateBack = {}
            )
        }
    }
}
