package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Ballot
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.add_schema
import curare.composeapp.generated.resources.curated_schemas
import curare.composeapp.generated.resources.fields_colon
import curare.composeapp.generated.resources.signed_by_the_group_on
import curare.composeapp.generated.resources.this_group_has_not_published_a_curated_schema_yet
import curare.composeapp.generated.resources.view_suggestions
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.toFormattedTimeAndDateString
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.CuratedSuggestionListRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupCuratedSchemaRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.EmptyState
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.buttons.BroadcastButton
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.GroupCuratedSchemasViewModel
import to.curare.compose.ui.view.state.GroupCuratedSchemasUIState
import kotlin.time.Instant

/**
 * The lists the group curates, as the schemas it signed for them.
 *
 * Pushed from its row on the group's screen, which counts the schemas and
 * nothing more. This is where they are read: one card per list, most recently
 * signed first, each with its queue and its broadcast under it, and -- for a
 * member holding a share of the key -- the way into the editor, on the card
 * for a revision and at the end for a new list.
 *
 * A schema exists to be read by people who were never in the room, so a member
 * without a share reads it like anybody else, and reads its queue, which the
 * group did not write. What they cannot do is propose a revision -- neither a
 * new schema nor an edit -- since proposing is a signature by the group and
 * `proposeSigningBatch` throws for a device holding no share.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupCuratedSchemasScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialGroupCuratedSchemasUIState: GroupCuratedSchemasUIState =
        GroupCuratedSchemasUIState.Loading,
    chatRepository: ChatRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupCuratedSchemasViewModel: GroupCuratedSchemasViewModel = viewModel(
        factory = GroupCuratedSchemasViewModel.factory(
            chatRoomId = chatRoomId,
            initialGroupCuratedSchemasUIState = initialGroupCuratedSchemasUIState,
            chatRepository = chatRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupCuratedSchemasUIState == GroupCuratedSchemasUIState.Loading) {
            groupCuratedSchemasViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.curated_schemas),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupCuratedSchemasViewModel.groupCuratedSchemasUIState) { uiState ->
            when (val state = uiState) {
                is GroupCuratedSchemasUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument,
                    // and reading it again with the same one fails the same way.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupCuratedSchemasUIState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(padding)
                            .padding(MaterialTheme.spacing.screenMargin),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                    ) {
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                        LoadingDataIndicator(fillScreen = false)
                    }
                }

                is GroupCuratedSchemasUIState.Loaded -> {
                    // The one way into the editor, for a revision and for a new
                    // list alike: the editor takes the identifier of the schema
                    // to revise, and null for one that does not exist yet.
                    val edit: (String?) -> Unit = { identifier ->
                        onNavigateToRoute.invoke(
                            EditGroupCuratedSchemaRoute(
                                activeUserPublicKey = activeUserPublicKey,
                                chatRoomId = chatRoomId,
                                relayHint = relayHint,
                                identifier = identifier
                            )
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.space250),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        if (state.groupCuratedSchemas.isEmpty()) {
                            item {
                                EmptyState(
                                    message = stringResource(
                                        Res.string.this_group_has_not_published_a_curated_schema_yet
                                    ),
                                    action = if (state.canSign) {
                                        { AddSchemaButton(onClick = { edit(null) }) }
                                    } else {
                                        null
                                    }
                                )
                            }
                        } else {
                            items(
                                items = state.groupCuratedSchemas,
                                key = { curated -> curated.signedEvent.id }
                            ) { curated ->
                                // The card and its queue as one item, so the
                                // button stays under the schema it is about
                                // however the list is arranged.
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    GroupCuratedSchemaCard(
                                        curatedSchema = curated,
                                        // Into the editor for a member who could
                                        // propose a revision, and a plain card for
                                        // one who could not: the screen's gate,
                                        // applied to the card because each schema
                                        // is edited on its own.
                                        onEdit = if (state.canSign) {
                                            { edit(curated.identifier) }
                                        } else {
                                            null
                                        }
                                    )

                                    // Under every schema and behind no gate,
                                    // because the queue is the one thing here
                                    // the group did not write: a schema exists
                                    // to be answered by strangers, and reading
                                    // the answers takes no share of the key.
                                    // Per card because each list has its own
                                    // queue.
                                    //
                                    // Beside the broadcast button rather than
                                    // over it, wrapping where the two will not
                                    // fit on one line: both are about this one
                                    // card and a column of buttons under every
                                    // schema would read as a menu.
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(
                                            MaterialTheme.spacing.itemGap,
                                            Alignment.CenterHorizontally
                                        )
                                    ) {
                                        TextButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    CuratedSuggestionListRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        relayHint = relayHint,
                                                        identifier = curated.identifier
                                                    )
                                                )
                                            }
                                        ) {
                                            Icon(Icons.Default.Inbox, contentDescription = Decorative)

                                            Spacer(
                                                modifier = Modifier.width(MaterialTheme.spacing.space125)
                                            )

                                            Text(stringResource(Res.string.view_suggestions))
                                        }

                                        BroadcastButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    BroadcastGroupSignedEventRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        relayHint = relayHint,
                                                        signedEventId = curated.signedEvent.id
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            if (state.canSign) {
                                item {
                                    AddSchemaButton(onClick = { edit(null) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One list the group curates, as the schema it signed for it.
 *
 * Named by the list -- the domain where it claims one, else the name -- because
 * that is what a suggester sees the list called; the title under it is the label
 * the event carries. The fields are listed by label rather than counted, since
 * what a reader wants to know about a list is what an entry in it asks for, and
 * "13 fields" answers a different question.
 *
 * The visibility is on the last line beside the date, because who may suggest is
 * the one fact on the card a member might act on -- a closed list is one they
 * would have to be named on.
 *
 * [onEdit] null draws a card that cannot be opened. It is the screen's gate
 * applied per card: a schema is edited on its own, so the way in has to be on it.
 */
@Composable
private fun GroupCuratedSchemaCard(
    curatedSchema: GroupCuratedSchema,
    onEdit: (() -> Unit)?
) {
    val content: @Composable () -> Unit = {
        ListItem(
            leadingContent = {
                Icon(Icons.Default.Ballot, contentDescription = Decorative)
            },
            trailingContent = onEdit?.let {
                { Icon(Icons.Default.Edit, contentDescription = "Edit schema") }
            },
            headlineContent = {
                Text(
                    text = curatedSchema.schema.displayName(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    Text(
                        text = curatedSchema.schema.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = curatedSchema.schema.description,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = stringResource(
                            Res.string.fields_colon,
                            curatedSchema.schema.fields.joinToString { it.labelOrName() }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = listOfNotNull(
                            curatedSchema.schema.visibility?.let { stringResource(it.label()) },
                            stringResource(
                                Res.string.signed_by_the_group_on,
                                curatedSchema.signedAt.toFormattedTimeAndDateString()
                            )
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (onEdit != null) {
        Card(modifier = Modifier.fillMaxWidth(), onClick = onEdit) { content() }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * The way into `EditGroupCuratedSchemaScreen` for a list that does not exist
 * yet. The icon is decorative because the label is beside it.
 */
@Composable
private fun AddSchemaButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = Decorative)

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

        Text(stringResource(Res.string.add_schema))
    }
}

@ConformancePreviews
@Composable
private fun GroupCuratedSchemasScreenPreview() {
    val chatRoomId = "d".repeat(64)

    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupCuratedSchemasScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                initialGroupCuratedSchemasUIState = GroupCuratedSchemasUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = chatRoomId,
                            userPublicKey = "a".repeat(64),
                            subject = "Translation room",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = "state"
                        )
                    ),
                    // Built rather than read out of a signed event: the reading
                    // checks a real signature, and there is no real one here.
                    groupCuratedSchemas = listOf(
                        GroupCuratedSchema(
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
                                title = "Films worth translating suggestion",
                                name = "Films worth translating",
                                description = "Things the group would translate, with a link to each.",
                                visibility = CuratedVisibility.Public,
                                fields = listOf(CuratedField.IDENTIFIER, CuratedField.TITLE),
                                relays = listOf("wss://relay.one.example")
                            )
                        )
                    ),
                    canSign = true
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
