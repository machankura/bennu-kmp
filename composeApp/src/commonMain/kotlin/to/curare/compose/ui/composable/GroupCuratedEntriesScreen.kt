package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.accepted_by_every_relay_for_n_of_m_entries
import curare.composeapp.generated.resources.accepted_by_n_of_m_relays
import curare.composeapp.generated.resources.broadcast_all
import curare.composeapp.generated.resources.curated_entries
import curare.composeapp.generated.resources.in_list
import curare.composeapp.generated.resources.no_relays_to_send_it_to
import curare.composeapp.generated.resources.relay_and_answer
import curare.composeapp.generated.resources.relay_sending
import curare.composeapp.generated.resources.sending_entry_n_of_m
import curare.composeapp.generated.resources.signed_by_the_group_on
import curare.composeapp.generated.resources.suggested_by
import curare.composeapp.generated.resources.this_group_has_not_accepted_anything_into_a_list_yet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.extensions.shortened
import to.curare.compose.extensions.toFormattedTimeAndDateString
import to.curare.compose.network.dto.RelayDTO
import to.curare.compose.network.relays.EventPublishTransport
import to.curare.compose.network.relays.NostrPublishResult
import to.curare.compose.nostr.GroupCuratedEntry
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedEntryEvent
import to.curare.compose.nostr.curated.CuratedField
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.curated.CuratedVisibility
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.EmptyState
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.buttons.BroadcastButton
import to.curare.compose.ui.composable.widgets.buttons.ExtendedFab
import to.curare.compose.ui.composable.widgets.label
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.GroupCuratedEntriesViewModel
import to.curare.compose.ui.view.model.GroupCuratedEntriesViewModel.BroadcastAllProgress
import to.curare.compose.ui.view.model.GroupCuratedEntriesViewModel.EntryBroadcast
import to.curare.compose.ui.view.state.GroupCuratedEntriesUIState
import kotlin.time.Instant

/**
 * The entries the group has accepted into the lists it curates, newest first.
 *
 * Pushed from its row on the group's screen, which counts the entries and
 * nothing more. This is where they are read: one card per entry with a
 * broadcast under it, and one button at the bottom that broadcasts all of
 * them. No gate on any of it, because reading what the group decided takes
 * no share of the key, and neither does repeating its signature to a relay.
 *
 * The two broadcasts differ in what the member sees first. The button under
 * a card opens `BroadcastGroupSignedEventScreen`, where the relays are shown
 * and can be changed before the send; the one at the bottom sends every
 * entry straight to where that screen would have started, one after another,
 * and says under each card what its relays answered. See
 * `GroupCuratedEntriesViewModel.broadcastAll` for why one at a time.
 *
 * There is no way in to add one here, because an entry is accepted from the
 * queue it was suggested to, which is reached from its schema's card on the
 * `GroupCuratedSchemasScreen`. The empty state says as much.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCuratedEntriesScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialGroupCuratedEntriesUIState: GroupCuratedEntriesUIState =
        GroupCuratedEntriesUIState.Loading,
    chatRepository: ChatRepository,
    publishTransport: EventPublishTransport,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupCuratedEntriesViewModel: GroupCuratedEntriesViewModel = viewModel(
        factory = GroupCuratedEntriesViewModel.factory(
            chatRoomId = chatRoomId,
            initialGroupCuratedEntriesUIState = initialGroupCuratedEntriesUIState,
            chatRepository = chatRepository,
            publishTransport = publishTransport,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupCuratedEntriesUIState == GroupCuratedEntriesUIState.Loading) {
            groupCuratedEntriesViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.curated_entries),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        },
        bottomBar = {
            // Only over a list with something in it. The empty state already
            // says why there is nothing to send, and a button that sends
            // nothing under it would say the opposite.
            val loaded = groupCuratedEntriesViewModel.groupCuratedEntriesUIState
                as? GroupCuratedEntriesUIState.Loaded

            if (loaded != null && loaded.groupCuratedEntries.isNotEmpty()) {
                val canSend = !groupCuratedEntriesViewModel.isActionPending.value

                BottomAppBar(
                    actions = {},
                    floatingActionButton = {
                        ExtendedFab(
                            label = stringResource(Res.string.broadcast_all),
                            icon = { Icon(Icons.Default.CellTower, contentDescription = Decorative) },
                            enabled = canSend,
                            onClick = {
                                groupCuratedEntriesViewModel.broadcastAll()
                            }
                        )
                    }
                )
            }
        }
    ) { padding ->
        ScreenStateTransition(groupCuratedEntriesViewModel.groupCuratedEntriesUIState) { uiState ->
            when (val state = uiState) {
                is GroupCuratedEntriesUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument,
                    // and reading it again with the same one fails the same way.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupCuratedEntriesUIState.Loading -> {
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

                is GroupCuratedEntriesUIState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.space250),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        if (state.groupCuratedEntries.isEmpty()) {
                            item {
                                EmptyState(
                                    message = stringResource(
                                        Res.string.this_group_has_not_accepted_anything_into_a_list_yet
                                    )
                                )
                            }
                        } else {
                            // The last press of "Broadcast all", summed up, above
                            // the cards that break it down. Persistent rather than
                            // a snackbar, because it is the answer the member came
                            // for and a snackbar would be gone before the last
                            // card had been read.
                            groupCuratedEntriesViewModel.broadcastAllProgress?.let { progress ->
                                item(key = "broadcast-all-progress") {
                                    BroadcastAllProgressLine(progress = progress)
                                }
                            }

                            items(
                                items = state.groupCuratedEntries,
                                key = { accepted -> accepted.signedEvent.id }
                            ) { accepted ->
                                // The card, what its relays answered, and its
                                // broadcast as one item, so all three stay under
                                // the entry they are about however the list is
                                // arranged. The answer sits above the button
                                // because the button is what a refusal calls for:
                                // it opens the screen where the relay can be
                                // changed and the send tried again.
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    GroupCuratedEntryCard(accepted = accepted)

                                    groupCuratedEntriesViewModel.entryBroadcasts[accepted.signedEvent.id]
                                        ?.let { EntryBroadcastLines(broadcast = it) }

                                    BroadcastButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                BroadcastGroupSignedEventRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = chatRoomId,
                                                    relayHint = relayHint,
                                                    signedEventId = accepted.signedEvent.id
                                                )
                                            )
                                        }
                                    )
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
 * Where "Broadcast all" has got to: which entry is out while the batch runs,
 * and how many were accepted everywhere once it is done -- in the primary
 * colour when that is all of them, and the error colour when the cards below
 * have a refusal to show.
 */
@Composable
private fun BroadcastAllProgressLine(progress: BroadcastAllProgress) {
    Text(
        text = when (progress) {
            is BroadcastAllProgress.Sending ->
                stringResource(Res.string.sending_entry_n_of_m, progress.entry, progress.total)
            is BroadcastAllProgress.Done ->
                stringResource(
                    Res.string.accepted_by_every_relay_for_n_of_m_entries,
                    progress.acceptedEverywhere,
                    progress.total
                )
        },
        style = MaterialTheme.typography.bodyMedium,
        color = when (progress) {
            is BroadcastAllProgress.Sending -> MaterialTheme.colorScheme.onSurfaceVariant
            is BroadcastAllProgress.Done -> if (progress.acceptedEverywhere == progress.total) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        },
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * What one entry's relays answered to "Broadcast all", under its card.
 *
 * The count first, in the words the one-event screen uses for its own; then
 * one line per relay that did not say OK, in the relay's words, because
 * "blocked: not on the allow list" is something a member can act on and a
 * count of two out of three is not. A relay that said OK is in the count and
 * nowhere else.
 */
@Composable
private fun EntryBroadcastLines(broadcast: EntryBroadcast) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.relatedGap),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (broadcast) {
            EntryBroadcast.Sending -> Text(
                text = stringResource(Res.string.relay_sending),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            EntryBroadcast.NoRelays -> Text(
                text = stringResource(Res.string.no_relays_to_send_it_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )

            is EntryBroadcast.Sent -> {
                Text(
                    text = stringResource(
                        Res.string.accepted_by_n_of_m_relays,
                        broadcast.accepted,
                        broadcast.asked
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (broadcast.isAcceptedEverywhere) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    textAlign = TextAlign.Center
                )

                broadcast.refusals.forEach { (relay, outcome) ->
                    Text(
                        text = stringResource(
                            Res.string.relay_and_answer,
                            // The host rather than the whole URL, as the
                            // one-event screen's rows: every relay here starts
                            // `wss://`.
                            relay.displayUrl(),
                            outcome.label()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * One entry the group accepted into a list it curates, as the list's own word.
 *
 * The title first, because every list has one; then which list, since a group
 * may curate several and an entry says nothing about its list on its face;
 * then the glance the queue row gives -- the fields that say what the entry
 * *is*, labelled as the schema labels them, and not the links -- and the body
 * where the list has one. The last line credits the suggester, when there was
 * one, and dates the group's decision, which is the fact a member reading the
 * screen came for.
 *
 * Not a way in to anything: the entry is revised from the queue it was
 * suggested to, where the suggestion it came from and the "Curated" mark sit
 * side by side, and the card's one action is the broadcast under it.
 */
@Composable
private fun GroupCuratedEntryCard(accepted: GroupCuratedEntry) {
    val schema = accepted.list.schema
    val entry = accepted.entry

    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = Decorative,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            headlineContent = {
                Text(
                    text = accepted.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    Text(
                        text = stringResource(Res.string.in_list, schema.displayName()),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

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
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = listOfNotNull(
                            entry.source?.let { stringResource(Res.string.suggested_by, it.pubKey.shortened()) },
                            stringResource(
                                Res.string.signed_by_the_group_on,
                                accepted.signedAt.toFormattedTimeAndDateString()
                            )
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

@ConformancePreviews
@Composable
private fun GroupCuratedEntriesScreenPreview() {
    val chatRoomId = "d".repeat(64)

    // Built rather than read out of signed events: the reading checks a real
    // signature, and there is no real one here.
    val films = GroupCuratedSchema(
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

    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupCuratedEntriesScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                initialGroupCuratedEntriesUIState = GroupCuratedEntriesUIState.Loaded(
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
                    groupCuratedEntries = listOf(
                        GroupCuratedEntry(
                            signedEvent = GroupSignedEvent(
                                id = "e".repeat(64),
                                chatRoomId = chatRoomId,
                                publicKey = chatRoomId,
                                kind = CuratedEntryEvent.CANONICAL_KIND,
                                tags = arrayOf(
                                    arrayOf("d", "magic-money"),
                                    arrayOf("title", "Magic Money")
                                ),
                                content = "",
                                signature = "f".repeat(128),
                                createdAt = Instant.fromEpochSeconds(1_700_000_900)
                            ),
                            list = films,
                            entry = CuratedEntry(
                                kind = CuratedEntryEvent.CANONICAL_KIND,
                                id = "e".repeat(64),
                                pubKey = chatRoomId,
                                createdAt = 1_700_000_900,
                                identifier = "magic-money",
                                title = "Magic Money",
                                values = mapOf(
                                    "identifier" to listOf("magic-money"),
                                    "title" to listOf("Magic Money"),
                                ),
                                source = null
                            )
                        )
                    )
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                publishTransport = object : EventPublishTransport {
                    override suspend fun publishEvent(
                        nostrEvent: NostrEvent,
                        relays: Set<RelayDTO>
                    ): Flow<NostrPublishResult> = emptyFlow()
                },
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
