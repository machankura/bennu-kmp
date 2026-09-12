package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
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
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.edit_relays
import curare.composeapp.generated.resources.no_relays_in_this_list
import curare.composeapp.generated.resources.not_set_yet
import curare.composeapp.generated.resources.relay_set_blocked
import curare.composeapp.generated.resources.relay_set_general
import curare.composeapp.generated.resources.relay_set_messages
import curare.composeapp.generated.resources.relay_set_search
import curare.composeapp.generated.resources.relays
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.GroupRelay
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.repository.ChatRepository
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupRelaysRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
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
import to.curare.compose.ui.view.model.GroupRelaysViewModel
import to.curare.compose.ui.view.state.GroupRelaysUIState
import kotlin.time.Instant

/**
 * Where the group has said it lives on nostr, one line per relay list.
 *
 * Pushed from its row on the group's screen, which says how many of the four
 * lists the group has agreed and nothing more. This is where they are read,
 * with a broadcast under each one the group has signed and -- for a member
 * holding a share of the key -- the way into the editor.
 *
 * There is no empty state to draw: every set gets a row whatever the group has
 * said, and the row is where "never agreed" and "agreed empty" are told apart.
 * See [GroupRelayListsCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupRelaysScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialGroupRelaysUIState: GroupRelaysUIState = GroupRelaysUIState.Loading,
    chatRepository: ChatRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupRelaysViewModel: GroupRelaysViewModel = viewModel(
        factory = GroupRelaysViewModel.factory(
            chatRoomId = chatRoomId,
            initialGroupRelaysUIState = initialGroupRelaysUIState,
            chatRepository = chatRepository,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupRelaysUIState == GroupRelaysUIState.Loading) {
            groupRelaysViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.relays),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupRelaysViewModel.groupRelaysUIState) { uiState ->
            when (val state = uiState) {
                is GroupRelaysUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument,
                    // and reading it again with the same one fails the same way.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupRelaysUIState.Loading -> {
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

                is GroupRelaysUIState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.space250),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        item {
                            GroupRelayListsCard(
                                relayLists = state.groupRelayLists,
                                onBroadcast = { signedEvent ->
                                    onNavigateToRoute.invoke(
                                        BroadcastGroupSignedEventRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            chatRoomId = chatRoomId,
                                            relayHint = relayHint,
                                            signedEventId = signedEvent.id
                                        )
                                    )
                                }
                            )
                        }

                        // The lists are worth reading either way; proposing them
                        // is a signature by the group, and `proposeSigningBatch`
                        // throws for a device holding no share of the key. Hidden
                        // rather than disabled, the way every gated way in on the
                        // group's screen is.
                        if (state.canSign) {
                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            EditGroupRelaysRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.Dns, contentDescription = Decorative)

                                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

                                    Text(stringResource(Res.string.edit_relays))
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
 * Where the group has said it lives on nostr, one line per relay list.
 *
 * One card holding four rows rather than four cards: they are read together -- the
 * question is whether the group is reachable, not what any single list says -- and
 * four cards of one line each is four dividers to scroll past for the same four
 * facts.
 *
 * **A list the group has never agreed reads "not set yet", and an agreed empty one
 * reads "no relays".** Those are different things: one is a group that has not got
 * round to it, and the other is a group that decided. Collapsing them would hide a
 * deliberate withdrawal behind an oversight.
 */
@Composable
private fun GroupRelayListsCard(
    relayLists: List<GroupRelayList>,
    onBroadcast: (GroupSignedEvent) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        relayLists.forEach { list ->
            ListItem(
                leadingContent = {
                    Icon(Icons.Default.Dns, contentDescription = Decorative)
                },
                headlineContent = {
                    Text(text = stringResource(list.set.summaryLabel()))
                },
                supportingContent = {
                    Text(
                        text = when {
                            !list.isAgreed -> stringResource(Res.string.not_set_yet)
                            list.relays.isEmpty() ->
                                stringResource(Res.string.no_relays_in_this_list)
                            // Hosts rather than URLs: every one of them starts
                            // `wss://`, so the scheme is the part that never
                            // distinguishes two entries.
                            else -> list.relays.joinToString { it.displayUrl() }
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )

            // Under the row it sends, and only where there is something signed
            // to send: a list the group has never agreed is not an event. Inside
            // the card because the four rows share one, so "under the event" has
            // to mean under the row -- and at the end, where M3 puts a card's
            // actions. An agreed empty list gets one too: a withdrawal is a
            // statement, and relays still holding the old list need to hear it.
            list.signedEvent?.let { signedEvent ->
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.compactPadding),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    BroadcastButton(onClick = { onBroadcast(signedEvent) })
                }
            }
        }
    }
}

/** The name of a relay list, for the row that summarises it. */
private fun GroupRelaySet.summaryLabel() = when (this) {
    GroupRelaySet.General -> Res.string.relay_set_general
    GroupRelaySet.Messages -> Res.string.relay_set_messages
    GroupRelaySet.Search -> Res.string.relay_set_search
    GroupRelaySet.Blocked -> Res.string.relay_set_blocked
}

@ConformancePreviews
@Composable
private fun GroupRelaysScreenPreview() {
    val chatRoomId = "d".repeat(64)

    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GroupRelaysScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = chatRoomId,
                relayHint = null,
                initialGroupRelaysUIState = GroupRelaysUIState.Loaded(
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
                    // One agreed list and three the group has not got round to,
                    // so the two things a row can say are both on the screen.
                    groupRelayLists = GroupRelaySet.entries.map { set ->
                        if (set == GroupRelaySet.General) {
                            GroupRelayList(
                                set = set,
                                signedEvent = GroupSignedEvent(
                                    id = "e".repeat(64),
                                    chatRoomId = chatRoomId,
                                    publicKey = chatRoomId,
                                    kind = set.kind,
                                    tags = emptyArray(),
                                    content = "",
                                    signature = "f".repeat(128),
                                    createdAt = Instant.fromEpochSeconds(1_700_000_000)
                                ),
                                relays = listOf(
                                    GroupRelay(RelayUrlNormalizer.normalize("wss://relay.one.example"))
                                )
                            )
                        } else {
                            GroupRelayList(set = set, signedEvent = null, relays = emptyList())
                        }
                    },
                    canSign = true
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
