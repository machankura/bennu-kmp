package to.curare.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.nostr.GroupRelay
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.nostr.GroupRelaySet
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.composable.navigation.routes.FrostSigningRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.EmptyState
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.rememberNotifier
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.EditGroupRelaysViewModel
import to.curare.compose.ui.view.state.EditGroupRelaysUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.add_relay
import curare.composeapp.generated.resources.could_not_ask_the_group_to_sign_these_relays
import curare.composeapp.generated.resources.edit_relays
import curare.composeapp.generated.resources.eg_wss_relay_example_com
import curare.composeapp.generated.resources.no_relays_in_this_list
import curare.composeapp.generated.resources.nothing_has_changed_to_propose
import curare.composeapp.generated.resources.propose_relays
import curare.composeapp.generated.resources.relay_read
import curare.composeapp.generated.resources.relay_set_blocked
import curare.composeapp.generated.resources.relay_set_blocked_purpose
import curare.composeapp.generated.resources.relay_set_general
import curare.composeapp.generated.resources.relay_set_general_purpose
import curare.composeapp.generated.resources.relay_set_messages
import curare.composeapp.generated.resources.relay_set_messages_purpose
import curare.composeapp.generated.resources.relay_set_search
import curare.composeapp.generated.resources.relay_set_search_purpose
import curare.composeapp.generated.resources.relay_url
import curare.composeapp.generated.resources.relay_write
import curare.composeapp.generated.resources.relays_are_public_a_group_cannot_encrypt
import curare.composeapp.generated.resources.that_is_not_a_relay_address
import curare.composeapp.generated.resources.that_relay_is_already_in_this_list
import curare.composeapp.generated.resources.the_group_signs_each_list_so_it_takes_a_quorum
import curare.composeapp.generated.resources.this_group_has_no_shared_key_to_sign_relays
import curare.composeapp.generated.resources.where_the_group_lives_on_nostr
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The four relay lists a group keeps, edited as one sitting.
 *
 * A tab per list, a field to add one, a row per relay with a way to drop it, and --
 * in the one list NIP-65 gives markers to -- a read and a write chip. The shape is
 * borrowed from Wisp's relay screen, which is the interface this app was asked to
 * match, with one deliberate departure.
 *
 * **The button proposes rather than publishes.** Wisp signs with the user's own key
 * and a tab's publish button is done the moment it is pressed. A group signs with a
 * quorum, so what leaves this screen is a proposal, and the screen hands over to
 * the session it opened. Nothing has changed until enough members sign.
 *
 * And it proposes **every list that changed at once**, rather than the tab in front
 * of you. Four sessions for one sitting would ask a quorum the same question four
 * times over what is plainly one decision -- see `EditGroupRelaysViewModel`, which
 * carries the reasoning and works out what actually differs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditGroupRelaysScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialEditGroupRelaysUIState: EditGroupRelaysUIState = EditGroupRelaysUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
) {
    val editGroupRelaysViewModel: EditGroupRelaysViewModel = viewModel(
        factory = EditGroupRelaysViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialEditGroupRelaysUIState = initialEditGroupRelaysUIState,
            activeUserPublicKey = activeUserPublicKey,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Read out here rather than in a click handler: both are composable and an
    // onClick lambda is not. The scope is the caller's so a message survives this
    // screen being replaced by the signing session.
    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.could_not_ask_the_group_to_sign_these_relays)
    val nothingChanged = stringResource(Res.string.nothing_has_changed_to_propose)

    ScreenStateTransition(editGroupRelaysViewModel.editGroupRelaysUIState) { uiState ->
        when (val editGroupRelaysUIState = uiState) {
            is EditGroupRelaysUIState.Error -> {
                // Nothing to retry: the room came from a navigation argument, and
                // reading it again with the same one fails the same way.
                ErrorState(message = editGroupRelaysUIState.message, onRetry = null)
            }

            is EditGroupRelaysUIState.Loaded -> {
                val urlFieldState = rememberTextFieldState()
                val selectedSet = editGroupRelaysViewModel.selectedSet
                val canSign = editGroupRelaysUIState.canSign

                // The editor's working copy comes from the state rather than from
                // whatever loaded it, so a screen handed a loaded state -- a
                // preview, a layout test -- fills in the same as the app does.
                // Seeding leaves edits alone, so re-running this changes nothing.
                LaunchedEffect(editGroupRelaysUIState.signed) {
                    editGroupRelaysViewModel.seedWorkingCopy(editGroupRelaysUIState.signed)
                }

                // A refusal is about what is in the field, so it goes the moment
                // the field changes rather than sitting under a URL that has since
                // been corrected.
                LaunchedEffect(urlFieldState) {
                    snapshotFlow { urlFieldState.text.toString() }
                        .collect { editGroupRelaysViewModel.clearAddFailure() }
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
                                editGroupRelaysUIState.localChatRoom.RenderChatRoomTitleText()
                            }
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

                                        editGroupRelaysViewModel.proposeRelayLists(
                                            localChatRoom =
                                                editGroupRelaysUIState.localChatRoom,
                                            signed = editGroupRelaysUIState.signed,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back
                                                // to the group. The lists have not
                                                // changed yet -- they change when a
                                                // quorum signs -- so landing on a
                                                // group still showing the old ones
                                                // would read as a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            // Both stay on the form with the edits
                                            // still in it. One is a question and
                                            // one is a failure, and neither is a
                                            // reason to throw away the typing.
                                            onNothingToPropose = { notify(nothingChanged) },
                                            onFailure = { notify(couldNotPropose) }
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Draw,
                                        contentDescription = "Propose relays"
                                    )
                                    Text(stringResource(Res.string.propose_relays))
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        PrimaryScrollableTabRow(
                            modifier = Modifier.padding(MaterialTheme.spacing.compactPadding),
                            edgePadding = 10.dp,
                            selectedTabIndex = selectedSet.ordinal,
                        ) {
                            GroupRelaySet.entries.forEach { set ->
                                Tab(
                                    selected = set == selectedSet,
                                    onClick = { editGroupRelaysViewModel.selectSet(set) },
                                    text = { Text(text = stringResource(set.label())) }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = MaterialTheme.spacing.screenMargin),
                            verticalArrangement = Arrangement.spacedBy(
                                MaterialTheme.spacing.itemGap
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(Res.string.where_the_group_lives_on_nostr),
                                style = MaterialTheme.typography.titleSmall
                            )

                            // What this tab's list is actually for. Four relay lists
                            // is four questions nobody can be expected to hold apart
                            // from their names alone.
                            Text(
                                text = stringResource(selectedSet.purpose()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = stringResource(
                                    Res.string.the_group_signs_each_list_so_it_takes_a_quorum
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            if (selectedSet == GroupRelaySet.Blocked) {
                                // Said where it matters. A person's client keeps
                                // this list encrypted, and somebody blocking a relay
                                // here should know the group cannot.
                                Text(
                                    text = stringResource(
                                        Res.string.relays_are_public_a_group_cannot_encrypt
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }

                            if (!canSign) {
                                Text(
                                    text = stringResource(
                                        Res.string.this_group_has_no_shared_key_to_sign_relays
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.weight(1f)
                                        .background(BottomAppBarDefaults.containerColor),
                                    state = urlFieldState,
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        disabledBorderColor = Color.Transparent
                                    ),
                                    leadingIcon = {
                                        Icon(Icons.Default.Dns, contentDescription = Decorative)
                                    },
                                    label = { Text(stringResource(Res.string.relay_url)) },
                                    placeholder = {
                                        Text(stringResource(Res.string.eg_wss_relay_example_com))
                                    },
                                    isError = editGroupRelaysViewModel.addFailure != null
                                )

                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

                                IconButton(
                                    onClick = {
                                        if (
                                            editGroupRelaysViewModel.addRelay(
                                                urlFieldState.text.toString()
                                            )
                                        ) {
                                            urlFieldState.clearText()
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add relay"
                                    )
                                }
                            }

                            // Named rather than silent. An add button that does
                            // nothing for a URL it refuses is indistinguishable from
                            // a missed tap.
                            editGroupRelaysViewModel.addFailure?.let { failure ->
                                Text(
                                    text = when (failure) {
                                        EditGroupRelaysViewModel.AddFailure.NotARelay ->
                                            stringResource(Res.string.that_is_not_a_relay_address)
                                        EditGroupRelaysViewModel.AddFailure.AlreadyListed ->
                                            stringResource(
                                                Res.string.that_relay_is_already_in_this_list
                                            )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        val relays = editGroupRelaysViewModel.relaysOf(selectedSet)

                        if (relays.isEmpty()) {
                            EmptyState(
                                message = stringResource(Res.string.no_relays_in_this_list),
                                icon = Icons.Default.Dns
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = MaterialTheme.spacing.screenMargin)
                            ) {
                                items(items = relays, key = { it.url.url }) { relay ->
                                    RelayRow(
                                        relay = relay,
                                        hasReadWriteMarkers = selectedSet.hasReadWriteMarkers,
                                        onToggleRead = {
                                            editGroupRelaysViewModel
                                                .toggleRead(selectedSet, relay)
                                        },
                                        onToggleWrite = {
                                            editGroupRelaysViewModel
                                                .toggleWrite(selectedSet, relay)
                                        },
                                        onRemove = {
                                            editGroupRelaysViewModel
                                                .removeRelay(selectedSet, relay)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            EditGroupRelaysUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.screenMargin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap)
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.emphasisGap))

                    Text(
                        text = stringResource(Res.string.edit_relays),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(fillScreen = false)
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialEditGroupRelaysUIState == EditGroupRelaysUIState.Loading) {
            editGroupRelaysViewModel.initiateEditGroupRelays()
        }
    }
}

/**
 * The tab's name, and the sentence saying what its list decides.
 *
 * Kept here rather than on [GroupRelaySet] so the enum stays what it is -- four
 * nostr kinds and how to read and write them -- with no catalogue behind it. A
 * `when` rather than a field on each entry, so adding a set is a compile error here
 * rather than a tab with no name.
 */
private fun GroupRelaySet.label(): StringResource = when (this) {
    GroupRelaySet.General -> Res.string.relay_set_general
    GroupRelaySet.Messages -> Res.string.relay_set_messages
    GroupRelaySet.Search -> Res.string.relay_set_search
    GroupRelaySet.Blocked -> Res.string.relay_set_blocked
}

private fun GroupRelaySet.purpose(): StringResource = when (this) {
    GroupRelaySet.General -> Res.string.relay_set_general_purpose
    GroupRelaySet.Messages -> Res.string.relay_set_messages_purpose
    GroupRelaySet.Search -> Res.string.relay_set_search_purpose
    GroupRelaySet.Blocked -> Res.string.relay_set_blocked_purpose
}

/**
 * One relay of one list.
 *
 * The read and write chips only in [hasReadWriteMarkers], which is NIP-65 alone --
 * the other three kinds have a relay in the list or not, and a chip there would
 * offer a distinction the event cannot carry.
 */
@Composable
private fun RelayRow(
    relay: GroupRelay,
    hasReadWriteMarkers: Boolean,
    onToggleRead: () -> Unit,
    onToggleWrite: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.relatedGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            Text(
                // The host rather than the whole URL: every relay here starts
                // `wss://` -- nothing else is allowed in -- so the scheme is four
                // characters of nothing in front of the part that differs.
                text = relay.displayUrl(),
                style = MaterialTheme.typography.bodyMedium
            )

            if (hasReadWriteMarkers) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                ) {
                    FilterChip(
                        selected = relay.read,
                        onClick = onToggleRead,
                        label = { Text(stringResource(Res.string.relay_read)) }
                    )
                    FilterChip(
                        selected = relay.write,
                        onClick = onToggleWrite,
                        label = { Text(stringResource(Res.string.relay_write)) }
                    )
                }
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove relay",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@ConformancePreviews
@Composable
private fun EditGroupRelaysScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            EditGroupRelaysScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialEditGroupRelaysUIState = EditGroupRelaysUIState.Loaded(
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
                    // Unsigned lists, which is a group opening this for the first
                    // time -- so the editor fills in from `GroupRelaySet.defaults`
                    // and the preview shows the relay a new group actually starts
                    // with rather than an empty tab.
                    signed = GroupRelayList.allAmong(emptyList(), "publicKey"),
                    // A group that can sign, so the form renders in the state a
                    // member actually meets it in rather than greyed out.
                    canSign = true
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {}
            )
        }
    }
}
