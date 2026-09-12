package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Ballot
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Unsubscribe
import androidx.compose.material.icons.filled.WaterfallChart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.GroupKeyState
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.Participant
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalParticipant
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.MantraRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.navigation.routes.DkgRitualRoute
import to.curare.compose.ui.composable.navigation.routes.AddGroupPostRoute
import to.curare.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupCuratedSchemaRoute
import to.curare.compose.ui.composable.navigation.routes.CuratedSuggestionListRoute
import to.curare.compose.ui.composable.navigation.routes.ProposeGroupEventRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupNostrProfileRoute
import to.curare.compose.ui.composable.navigation.routes.EditGroupRelaysRoute
import to.curare.compose.ui.composable.navigation.routes.ProposalListRoute
import to.curare.compose.ui.composable.navigation.routes.SearchMemberToAddToChatRoomRoute
import to.curare.compose.extensions.shortened
import to.curare.compose.extensions.toFormattedTimeAndDateString
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import to.curare.compose.ui.composable.widgets.rememberNotifier
import to.curare.compose.ui.composable.widgets.profile.ProfileAvatar
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.ChatRoomDetailViewModel
import to.curare.compose.ui.view.state.ChatRoomDetailUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.run_by_members
import curare.composeapp.generated.resources.nostr_profile
import curare.composeapp.generated.resources.relays
import curare.composeapp.generated.resources.posts
import curare.composeapp.generated.resources.add_post
import curare.composeapp.generated.resources.a_reply
import curare.composeapp.generated.resources.curated_entries
import curare.composeapp.generated.resources.curated_schemas
import curare.composeapp.generated.resources.in_list
import curare.composeapp.generated.resources.suggested_by
import curare.composeapp.generated.resources.this_group_has_not_accepted_anything_into_a_list_yet
import curare.composeapp.generated.resources.this_group_has_not_published_a_curated_schema_yet
import curare.composeapp.generated.resources.add_schema
import curare.composeapp.generated.resources.view_suggestions
import curare.composeapp.generated.resources.fields_colon
import curare.composeapp.generated.resources.propose_event
import curare.composeapp.generated.resources.posted_on
import curare.composeapp.generated.resources.this_group_has_not_posted_anything_yet
import curare.composeapp.generated.resources.edit_relays
import curare.composeapp.generated.resources.not_set_yet
import curare.composeapp.generated.resources.no_relays_in_this_list
import curare.composeapp.generated.resources.relay_set_general
import curare.composeapp.generated.resources.relay_set_messages
import curare.composeapp.generated.resources.relay_set_search
import curare.composeapp.generated.resources.relay_set_blocked
import curare.composeapp.generated.resources.no_name_on_this_profile
import curare.composeapp.generated.resources.edit_nostr_profile
import curare.composeapp.generated.resources.signed_by_the_group_on
import curare.composeapp.generated.resources.this_group_has_not_said_anything_about_itself
import curare.composeapp.generated.resources.parent_group
import curare.composeapp.generated.resources.a_subgroup
import curare.composeapp.generated.resources.created_you_are_not_a_member
import curare.composeapp.generated.resources.certified_not_yet_created
import curare.composeapp.generated.resources.no_subgroups_have_been_made_by_this_group
import curare.composeapp.generated.resources.add_subgroup
import curare.composeapp.generated.resources.subgroups
import to.curare.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import to.curare.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import to.curare.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import to.curare.compose.managers.SubgroupManager
import to.curare.compose.nostr.GroupCuratedEntry
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupNostrProfile
import to.curare.compose.nostr.GroupPost
import to.curare.compose.nostr.GroupRelayList
import to.curare.compose.nostr.GroupRelaySet
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.delete_group
import curare.composeapp.generated.resources.direct_message_detail
import curare.composeapp.generated.resources.direct_message_functionality_will_be_here
import curare.composeapp.generated.resources.invite_new_member
import curare.composeapp.generated.resources.leave_group
import curare.composeapp.generated.resources.members_2
import curare.composeapp.generated.resources.proposals
import curare.composeapp.generated.resources.reindex_events
import curare.composeapp.generated.resources.shared_key
import curare.composeapp.generated.resources.signing_key
import curare.composeapp.generated.resources.the_key_this_room_signs_as
import curare.composeapp.generated.resources.what_the_group_signed
import curare.composeapp.generated.resources.this_room_signs_as
import curare.composeapp.generated.resources.derivation_path
import curare.composeapp.generated.resources.key_ceremony
import curare.composeapp.generated.resources.agreed_on
import curare.composeapp.generated.resources.broadcast
import curare.composeapp.generated.resources.the_signed_event
import curare.composeapp.generated.resources.copy_the_signed_event
import curare.composeapp.generated.resources.copied_the_signed_event
import curare.composeapp.generated.resources.this_device_does_not_hold_the_event
import curare.composeapp.generated.resources.event_s_still_unreadable
import curare.composeapp.generated.resources.recovered_of_event_s
import curare.composeapp.generated.resources.recovered_of_still_unreadable
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.theme.ConformancePreviews
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatRoomDetailScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialChatRoomDetailUIState: ChatRoomDetailUIState = ChatRoomDetailUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    mantraRepository: MantraRepository,
    onNavigateToRoute: (Route) -> Unit,
    onPopBackToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val chatRoomDetailViewModel: ChatRoomDetailViewModel = viewModel(
        factory = ChatRoomDetailViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialChatRoomDetailUIState = initialChatRoomDetailUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            mantraRepository = mantraRepository,
            activeUserPublicKey = activeUserPublicKey
        )
    )

    ScreenStateTransition(chatRoomDetailViewModel.chatRoomDetailUIState) { uiState ->
    when (val chatRoomDetailUIState = uiState) {
            is ChatRoomDetailUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = chatRoomDetailUIState.message,
                    )
                }
            }
            is ChatRoomDetailUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                chatRoomDetailUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        var isShowingGroupKeyState by rememberSaveable { mutableStateOf(false) }

                        // The one way into the broadcast screen, for every signed
                        // event in the identity block below.
                        val broadcast: (GroupSignedEvent) -> Unit = { signedEvent ->
                            onNavigateToRoute.invoke(
                                BroadcastGroupSignedEventRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = chatRoomId,
                                    relayHint = relayHint,
                                    signedEventId = signedEvent.id
                                )
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(MaterialTheme.spacing.space250),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                        ) {
                            // First on the screen, because it is the fact the rest
                            // of the room's signed work stands on: which key the
                            // room signs as decides whether anything it has signed
                            // will be believed anywhere.
                            //
                            // Absent rather than empty on a room the group has said
                            // nothing about. There is no half state to report -- a
                            // room either has one a quorum signed or has none -- and
                            // the shared key entry further down is where somebody
                            // goes to make one.
                            chatRoomDetailUIState.groupKeyState?.let { groupKeyState ->
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { isShowingGroupKeyState = true }
                                    ) {
                                        ListItem(
                                            leadingContent = {
                                                Icon(Icons.Default.Key, contentDescription = Decorative)
                                            },
                                            trailingContent = {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = Decorative
                                                )
                                            },
                                            headlineContent = {
                                                Text(text = stringResource(Res.string.signing_key))
                                            },
                                            supportingContent = {
                                                // The identity, not the group's root
                                                // key: this is the pubkey a reader
                                                // sees on everything the room signs,
                                                // and the room's own id. Truncated
                                                // here and whole in the sheet, where
                                                // it can be compared.
                                                Text(
                                                    text = groupKeyState.announcedBy
                                                        .take(16)
                                                        .inComparableGroups(),
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.MiddleEllipsis
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            // Directly under the signing key, because between them
                            // they are what the room *is*: the identity it signs as
                            // and whose child it is. Absent on a group that is
                            // nobody's child, and absent -- rather than guessed --
                            // on a subgroup this device holds no key state for,
                            // since an unverified lineage is not one worth showing.
                            chatRoomDetailUIState.parentChatRoomId?.let { parentChatRoomId ->
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ChatRoomDetailRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = parentChatRoomId,
                                                    relayHint = relayHint
                                                )
                                            )
                                        }
                                    ) {
                                        ListItem(
                                            leadingContent = {
                                                Icon(
                                                    Icons.Default.AccountTree,
                                                    contentDescription = Decorative
                                                )
                                            },
                                            trailingContent = {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = Decorative
                                                )
                                            },
                                            headlineContent = {
                                                Text(text = stringResource(Res.string.parent_group))
                                            },
                                            supportingContent = {
                                                Text(
                                                    text = parentChatRoomId
                                                        .take(16)
                                                        .inComparableGroups(),
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.MiddleEllipsis
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                chatRoomDetailUIState.localChatRoom.chatRoom.description?.let {
                                    Text(
                                        text = it,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }

                            item {
                                HorizontalDivider()
                            }

                            // Under the room's identity and above its work, because
                            // that is what it is: what the rest of nostr reads for
                            // the key the signing key row names. The room's subject
                            // and description above are this device's names for it;
                            // this is the group's own, signed by the group.
                            //
                            // Marmot rooms only, and absent rather than empty in a
                            // NIP-17 one. A NIP-17 room's id is not a key it can
                            // sign as, so it has no nostr identity at all -- and a
                            // section saying it has not described one yet would
                            // promise something that is never coming.
                            if (chatRoomDetailUIState.localChatRoom.chatRoom.mlsGroupState != null) {
                                item {
                                    Text(
                                        text = stringResource(Res.string.nostr_profile),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                item {
                                    chatRoomDetailUIState.groupNostrProfile?.let { groupNostrProfile ->
                                        // The card and its broadcast button as one
                                        // item, so the button stays under the event
                                        // it sends however the list is arranged.
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            GroupNostrProfileCard(
                                                groupNostrProfile = groupNostrProfile,
                                                publicKey = chatRoomId
                                            )

                                            // Behind no gate, here and under every
                                            // other signed event in the block: sending
                                            // what the group has already signed takes
                                            // no share of the key, since the
                                            // signature on it is the group's whoever
                                            // repeats it. Only *making* a statement
                                            // is gated.
                                            BroadcastButton(
                                                onClick = { broadcast(groupNostrProfile.signedEvent) }
                                            )
                                        }
                                    } ?: Text(
                                        stringResource(
                                            Res.string.this_group_has_not_said_anything_about_itself
                                        )
                                    )
                                }

                                // The profile is worth reading either way; proposing
                                // one is a signature by the group, and
                                // `proposeSigningBatch` throws for a device holding
                                // no share of the key. Hidden rather than disabled,
                                // the way the subgroup entry is.
                                if (chatRoomDetailUIState.canEditNostrProfile) {
                                    item {
                                        TextButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    EditGroupNostrProfileRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        relayHint = relayHint
                                                    )
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Badge,
                                                contentDescription = Decorative
                                            )

                                            Spacer(
                                                modifier = Modifier.width(
                                                    MaterialTheme.spacing.space125
                                                )
                                            )

                                            Text(stringResource(Res.string.edit_nostr_profile))
                                        }
                                    }
                                }

                                // Directly under the profile, because between them
                                // they are the whole of the group's account of
                                // itself on nostr: who it says it is, and where it
                                // says it can be found. Same gate, same reasons.
                                item {
                                    Text(
                                        text = stringResource(Res.string.relays),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                item {
                                    GroupRelayListsCard(
                                        relayLists = chatRoomDetailUIState.groupRelayLists,
                                        onBroadcast = broadcast
                                    )
                                }

                                if (chatRoomDetailUIState.canEditNostrProfile) {
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
                                            Icon(
                                                Icons.Default.Dns,
                                                contentDescription = Decorative
                                            )

                                            Spacer(
                                                modifier = Modifier.width(
                                                    MaterialTheme.spacing.space125
                                                )
                                            )

                                            Text(stringResource(Res.string.edit_relays))
                                        }
                                    }
                                }

                                // Under the relay lists, because these are what the
                                // lists are *for*: the profile says who the group
                                // is, the relays say where to find it, and these are
                                // what a reader would find there.
                                item {
                                    Text(
                                        text = stringResource(Res.string.posts),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                if (chatRoomDetailUIState.groupPosts.isEmpty()) {
                                    item {
                                        Text(
                                            stringResource(
                                                Res.string.this_group_has_not_posted_anything_yet
                                            )
                                        )
                                    }
                                } else {
                                    items(
                                        items = chatRoomDetailUIState.groupPosts,
                                        key = { post -> post.signedEvent.id }
                                    ) { post ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            GroupPostCard(
                                                post = post,
                                                publicKey = chatRoomId,
                                                groupNostrProfile =
                                                    chatRoomDetailUIState.groupNostrProfile
                                            )

                                            BroadcastButton(
                                                onClick = { broadcast(post.signedEvent) }
                                            )
                                        }
                                    }
                                }

                                if (chatRoomDetailUIState.canEditNostrProfile) {
                                    item {
                                        TextButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    AddGroupPostRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        relayHint = relayHint
                                                    )
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Campaign,
                                                contentDescription = Decorative
                                            )

                                            Spacer(
                                                modifier = Modifier.width(
                                                    MaterialTheme.spacing.space125
                                                )
                                            )

                                            Text(stringResource(Res.string.add_post))
                                        }
                                    }
                                }

                                // Under the posts, because a schema is the other
                                // thing a group publishes for strangers to answer:
                                // a post is the group speaking, a schema is the
                                // group asking -- for entries to a list it will
                                // then curate, by quorum, under the same key. What
                                // it curated follows, in the section below.
                                item {
                                    Text(
                                        text = stringResource(Res.string.curated_schemas),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                if (chatRoomDetailUIState.groupCuratedSchemas.isEmpty()) {
                                    item {
                                        Text(
                                            stringResource(
                                                Res.string.this_group_has_not_published_a_curated_schema_yet
                                            )
                                        )
                                    }
                                } else {
                                    items(
                                        items = chatRoomDetailUIState.groupCuratedSchemas,
                                        key = { curated -> curated.signedEvent.id }
                                    ) { curated ->
                                        // The card and its queue as one item, so
                                        // the button stays under the schema it is
                                        // about however the list is arranged.
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            GroupCuratedSchemaCard(
                                                curatedSchema = curated,
                                                // Into the editor for a member who
                                                // could propose a revision, and a plain
                                                // card for one who could not: the same
                                                // gate as every button in this block,
                                                // applied to the card because each
                                                // schema is edited on its own.
                                                onEdit = if (chatRoomDetailUIState.canEditNostrProfile) {
                                                    {
                                                        onNavigateToRoute.invoke(
                                                            EditGroupCuratedSchemaRoute(
                                                                activeUserPublicKey = activeUserPublicKey,
                                                                chatRoomId = chatRoomId,
                                                                relayHint = relayHint,
                                                                identifier = curated.identifier
                                                            )
                                                        )
                                                    }
                                                } else {
                                                    null
                                                }
                                            )

                                            // Under every schema and behind no
                                            // gate, because the queue is the one
                                            // thing here the group did not write:
                                            // a schema exists to be answered by
                                            // strangers, and reading the answers
                                            // takes no share of the key. Per card
                                            // because each list has its own queue.
                                            //
                                            // Beside the broadcast button rather
                                            // than over it, wrapping where the two
                                            // will not fit on one line: both are
                                            // about this one card and a column of
                                            // buttons under every schema would
                                            // read as a menu.
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
                                                    Icon(
                                                        Icons.Default.Inbox,
                                                        contentDescription = Decorative
                                                    )

                                                    Spacer(
                                                        modifier = Modifier.width(
                                                            MaterialTheme.spacing.space125
                                                        )
                                                    )

                                                    Text(stringResource(Res.string.view_suggestions))
                                                }

                                                BroadcastButton(
                                                    onClick = { broadcast(curated.signedEvent) }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (chatRoomDetailUIState.canEditNostrProfile) {
                                    item {
                                        TextButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    EditGroupCuratedSchemaRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        relayHint = relayHint,
                                                        identifier = null
                                                    )
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                                contentDescription = Decorative
                                            )

                                            Spacer(
                                                modifier = Modifier.width(
                                                    MaterialTheme.spacing.space125
                                                )
                                            )

                                            Text(stringResource(Res.string.add_schema))
                                        }
                                    }
                                }

                                // Under the schemas, because these are what a
                                // schema is *for*: the schema asks, strangers
                                // answer on the queue, and these are the answers
                                // the group put its key to. They close the
                                // identity block because everything in it is
                                // signed by that key and read by people who were
                                // never in the room, and an accepted entry is the
                                // last thing in that chain.
                                item {
                                    Text(
                                        text = stringResource(Res.string.curated_entries),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                if (chatRoomDetailUIState.groupCuratedEntries.isEmpty()) {
                                    item {
                                        Text(
                                            stringResource(
                                                Res.string.this_group_has_not_accepted_anything_into_a_list_yet
                                            )
                                        )
                                    }
                                } else {
                                    items(
                                        items = chatRoomDetailUIState.groupCuratedEntries,
                                        key = { accepted -> accepted.signedEvent.id }
                                    ) { accepted ->
                                        // The card and its broadcast as one item,
                                        // so the button stays under the entry it
                                        // sends. No gate on either: reading what
                                        // the group decided takes no share of the
                                        // key, and neither does repeating its
                                        // signature to a relay. There is no way
                                        // in to add one here, because an entry
                                        // is accepted from the queue it was
                                        // suggested to, on the schema's card
                                        // above.
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            GroupCuratedEntryCard(accepted = accepted)

                                            BroadcastButton(
                                                onClick = { broadcast(accepted.signedEvent) }
                                            )
                                        }
                                    }
                                }

                                // The untyped way into everything above it. Each
                                // section has a form that builds one kind of event;
                                // this takes any of those kinds as JSON, pasted from
                                // wherever it was made, and it lands in whichever
                                // section is its kind's once the quorum signs --
                                // the same reading, off the same signed events.
                                // Last in the block because it is about the block
                                // rather than about any one section of it, and
                                // behind the same gate for the same reason.
                                if (chatRoomDetailUIState.canEditNostrProfile) {
                                    item {
                                        TextButton(
                                            onClick = {
                                                onNavigateToRoute.invoke(
                                                    ProposeGroupEventRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        relayHint = relayHint
                                                    )
                                                )
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.DataObject,
                                                contentDescription = Decorative
                                            )

                                            Spacer(
                                                modifier = Modifier.width(
                                                    MaterialTheme.spacing.space125
                                                )
                                            )

                                            Text(stringResource(Res.string.propose_event))
                                        }
                                    }
                                }

                                item {
                                    HorizontalDivider()
                                }
                            }

                            item {
                                Text(
                                    text = stringResource(Res.string.subgroups),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            if (chatRoomDetailUIState.subgroups.isEmpty()) {
                                item {
                                    Text(stringResource(Res.string.no_subgroups_have_been_made_by_this_group))
                                }
                            } else {
                                items(
                                    items = chatRoomDetailUIState.subgroups,
                                    key = { subgroup -> subgroup.chatRoomId }
                                ) { subgroup ->
                                    SubgroupCard(
                                        subgroup = subgroup,
                                        onOpen = {
                                            // Into the child where this device has
                                            // it; at the certificate where it does
                                            // not, since that is the whole of what
                                            // is known and it is checkable.
                                            onNavigateToRoute.invoke(
                                                subgroup.room?.let {
                                                    ChatRoomDetailRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = it.chatRoom.id,
                                                        relayHint = relayHint
                                                    )
                                                } ?: NostrEventDetailRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    nostrEventId = subgroup.certificate.id
                                                )
                                            )
                                        }
                                    )
                                }
                            }

                            // Offered only where it can work: this device holds a
                            // share of the group's key, so it can open the
                            // certificate session, and the room is a Marmot one, so
                            // there is a group with admins to be a parent. Hidden
                            // rather than disabled, the way the shared key entry is.
                            if (chatRoomDetailUIState.canAddSubgroup) {
                                item {
                                    TextButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                SelectSubgroupAdminsRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    parentChatRoomId = chatRoomId,
                                                    relayHint = relayHint
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.AccountTree,
                                            contentDescription = Decorative
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(stringResource(Res.string.add_subgroup))
                                    }
                                }
                            }

                            item {
                                HorizontalDivider()
                            }

                            item {
                                Text(
                                    text = stringResource(Res.string.members_2),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            // Offering a ceremony to a room is offering it a key of
                            // its own, and only a NIP-17 room is in a position to
                            // want one: a Marmot room's id is derived from a key it
                            // already has, so a second ceremony there would produce
                            // a key the room could never answer to.
                            //
                            // A Marmot room does host ceremonies -- its subgroups'
                            // -- and those are reached from the transcript line that
                            // names the ceremony they are about, never from here,
                            // which has no way to say which.
                            if (chatRoomDetailUIState.localChatRoom.chatRoom.mlsGroupState == null) {
                                item {
                                    TextButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                DkgRitualRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    chatRoomId = chatRoomId
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Key,
                                            contentDescription = "Shared key"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(stringResource(Res.string.shared_key))
                                    }
                                }
                            }

                            // Every room, not only the one that holds the key. Most
                            // of what a group signs it signs in its Marmot room --
                            // its subgroups' certificates and key states included --
                            // so that is the last place this should be missing from.
                            //
                            // The transcript carries a proposal past as it happens;
                            // this is where a member goes to find one that has scrolled
                            // away, or to see what the group has signed.
                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ProposalListRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chatRoomId = chatRoomId
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Draw,
                                        contentDescription = "Proposals"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.proposals))
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        // Navigate to the invite user screen...
                                        onNavigateToRoute.invoke(
                                            SearchMemberToAddToChatRoomRoute(
                                                chatRoomId = chatRoomId,
                                                activeUserPublicKey = activeUserPublicKey,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.PersonAdd,
                                        contentDescription = "Invite new member"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.invite_new_member))
                                }
                            }

                            items(
                                items = chatRoomDetailUIState.localChatRoom.localParticipants,
                                key = { localParticipant -> localParticipant.participant.participantPublicKey}
                            ) { localParticipant ->

                                Card(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ImplementationPendingRoute(
                                                "Profile detail"
                                            )
                                        )
                                    }
                                ) {
                                    ListItem(
                                        leadingContent = {
                                            ProfileAvatar(
                                                publicKey = localParticipant.participant.participantPublicKey,
                                                profile = localParticipant.profile
                                            )
                                        },
                                        trailingContent = {
                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = "View profile"
                                            )
                                        },
                                        headlineContent = {
                                            Text(
                                                text = localParticipant.profile?.humanReadableNameOrPubkey() ?: localParticipant.participant.participantPublicKey
                                            )
                                        },
                                        supportingContent = {
                                            localParticipant.profile?.about?.let {
                                                Text(
                                                    text = it
                                                )
                                            }
                                        }

                                    )
                                }

                            }


                            item {
                                HorizontalDivider()
                            }

                            item {

                                TextButton(
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.secondary
                                    ),
                                    enabled = chatRoomDetailUIState.localChatRoom.chatRoom.leftGroupAt == null, // Disable after we leave group...
                                    onClick = {
                                        chatRoomDetailViewModel.leaveGroup(
                                            localChatRoom = chatRoomDetailUIState.localChatRoom,
                                            onPopBackToRoute = onPopBackToRoute
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Unsubscribe,
                                        contentDescription = "Leave group"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.leave_group))
                                }

                                // What it actually does: sets leftGroupAt and posts a line to
                                // the room. Said here because the button fires immediately --
                                // there is no confirmation step to say it in.
                                Text(
                                    text = "Posts a line to the room saying you left, and " +
                                        "lets you delete it from this device afterwards.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = MaterialTheme.spacing.space600,
                                        end = MaterialTheme.spacing.containerPadding,
                                        bottom = MaterialTheme.spacing.compactPadding,
                                    )
                                )
                            }

                            item {
                                TextButton(
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    enabled = chatRoomDetailUIState.localChatRoom.chatRoom.leftGroupAt != null,
                                    onClick = {
                                        chatRoomDetailViewModel.softDeleteGroup(
                                            localChatRoom = chatRoomDetailUIState.localChatRoom,
                                            onPopBackToRoute = onPopBackToRoute
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription = "Delete group"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.delete_group))
                                }

                                // A soft delete: it sets deletedAt on the local row. Saying
                                // "delete" without saying where would leave somebody believing
                                // the messages are gone from the relays and from the other
                                // members, which is the opposite of true.
                                Text(
                                    text = "Removes the room from this device. The messages " +
                                        "stay on the relays and with the other members.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = MaterialTheme.spacing.space600,
                                        end = MaterialTheme.spacing.containerPadding,
                                        bottom = MaterialTheme.spacing.compactPadding,
                                    )
                                )
                            }

                            // Only MLS rooms have group events to read again. A NIP-17
                            // room's messages are gift wraps, which carry no ordering
                            // for anything to go wrong with.
                            if (chatRoomDetailUIState.localChatRoom.chatRoom.mlsGroupState != null) {
                                item {
                                    HorizontalDivider()
                                }

                                item {
                                    ReindexMarmotGroupEventsButton(
                                        reindexState = chatRoomDetailViewModel.reindexState,
                                        onReindex = chatRoomDetailViewModel::reindexMarmotGroupEvents
                                    )
                                }
                            }
                        }

                        if (isShowingGroupKeyState) {
                            chatRoomDetailUIState.groupKeyState?.let { groupKeyState ->
                                GroupKeyStateSheet(
                                    groupKeyState = groupKeyState,
                                    signedEvent = chatRoomDetailUIState.signedGroupKeyStateEvent,
                                    onDismiss = { isShowingGroupKeyState = false }
                                )
                            }
                        }
                    }
                }
            }
            ChatRoomDetailUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        MaterialTheme.spacing.space250
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {

                    Spacer(
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(Res.string.direct_message_detail),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(Res.string.direct_message_functionality_will_be_here),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )

                    LoadingDataIndicator(
                        fillScreen = false
                    )

                    Spacer(
                        modifier = Modifier.weight(2f)
                    )
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialChatRoomDetailUIState == ChatRoomDetailUIState.Loading) {
            chatRoomDetailViewModel.initiateChatRoomDetail()
        }
    }
}

/**
 * What the group signed to say what this room signs with, and a way to take it
 * somewhere else.
 *
 * The fields first, because they are what a member came to read: the identity
 * the room signs as, the key behind it, the path between them, and which
 * ceremony made it. Then the event itself, which is the only part that can be
 * *checked* -- a signature by that same identity over those same fields -- and
 * so the only part worth copying.
 *
 * Whole values throughout, monospaced and wrapping. These exist to be compared
 * character by character, against another member's screen or against what a
 * relay reports, and a truncated key cannot be compared.
 *
 * [signedEvent] can be missing while [groupKeyState] is not -- a device that
 * kept the reading and lost the statement -- and the sheet says so rather than
 * showing the fields as though they were signed. It never happens the other way
 * round: a state is only ever written from an event that passed both checks.
 */
@Composable
private fun GroupKeyStateSheet(
    groupKeyState: GroupKeyState,
    signedEvent: GroupSignedEvent?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismiss = onDismiss) {
        GroupKeyStateSheetContent(groupKeyState = groupKeyState, signedEvent = signedEvent)
    }
}

/**
 * The sheet's body, apart from the sheet.
 *
 * Split out so it can be rendered and measured on its own -- a bottom sheet is a
 * popup with its own window, which a layout test cannot reach into. See
 * `GroupKeyStateSheetLayoutJvmTest`, which is checking the one thing that is not
 * obvious from reading this: that a 500-character line of JSON with almost no
 * spaces in it wraps inside the sheet rather than running off the side of it.
 */
@Composable
internal fun GroupKeyStateSheetContent(
    groupKeyState: GroupKeyState,
    signedEvent: GroupSignedEvent?
) {
    val clipboardManager = LocalClipboardManager.current

    // Read out here rather than in the click handler: both of these are
    // composable, and an onClick lambda is not.
    val notify = rememberNotifier(rememberCoroutineScope())
    val copied = stringResource(Res.string.copied_the_signed_event)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.what_the_group_signed),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        Text(
            text = stringResource(Res.string.the_key_this_room_signs_as),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

        // The identity every reader will see on this room's signed events, which
        // is also the room's own id -- see SharedKeyDerivation.marmotGroupId,
        // where those are one value.
        GroupKeyStateField(
            label = stringResource(Res.string.this_room_signs_as),
            value = groupKeyState.announcedBy.inComparableGroups()
        )

        GroupKeyStateField(
            label = stringResource(Res.string.shared_key),
            value = groupKeyState.thresholdPublicKey.inComparableGroups()
        )

        GroupKeyStateField(
            label = stringResource(Res.string.derivation_path),
            value = groupKeyState.derivationPath
        )

        GroupKeyStateField(
            label = stringResource(Res.string.key_ceremony),
            value = groupKeyState.dkgSessionId.inComparableGroups()
        )

        GroupKeyStateField(
            label = stringResource(Res.string.agreed_on),
            value = groupKeyState.announcedAt.toFormattedTimeAndDateString(),
            isMonospaced = false
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sectionGap))

        Text(
            text = stringResource(Res.string.the_signed_event),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.relatedGap))

        if (signedEvent == null) {
            Text(
                text = stringResource(Res.string.this_device_does_not_hold_the_event),
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        // As the group signed it, byte for byte. What is copied is exactly what
        // is shown, because the point of copying it is to hand somebody
        // something they can verify -- and a prettier rendering would be a
        // different string to the one whose id was hashed.
        val json = signedEvent.toEvent().toJson()

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
            Text(text = stringResource(Res.string.copy_the_signed_event))
        }
    }
}

/**
 * A long hex value in groups of eight, which is what makes it readable and what
 * lets it wrap at all.
 *
 * Both halves matter. A 64-character key is compared against another member's
 * screen a character at a time, and the eye loses its place in an undivided run
 * of hex -- the same reason a fingerprint or an account number is grouped.
 *
 * And a run of hex has no space in it, so Compose has nowhere to break the line:
 * it lays the whole thing out on one and lets it run off the side of the sheet,
 * where the end of the key cannot be read at all. The spaces are the break
 * opportunities. Nothing is copied from these -- the copy button takes the
 * signed event, which is not grouped -- so the display can be shaped for reading
 * without a paste losing the value.
 */
/**
 * What the group says about itself on nostr, as the group signed it.
 *
 * A preview rather than a profile screen: the name, the picture, the nostr address
 * and the first lines of the group's own account of itself, which is what a member
 * opening the group's details came here to check. The whole thing is one signed
 * event and the proposal list is where it can be read out in full.
 *
 * **The picture and the name come from the metadata; the tint comes from the key.**
 * [publicKey] is the room's id, which is the pubkey the group signs as, so a group
 * with no picture yet still gets the colour it has everywhere else in the app.
 *
 * The signing date is here because a group's profile is standing state with no
 * other clue to its age -- and because the alternative reading, that this arrived
 * from a relay a moment ago, is exactly what has not happened. Nothing on this card
 * was published by being signed -- see `GroupNostrProfile` -- and the broadcast
 * button under it is how it gets to a relay.
 */
@Composable
private fun GroupNostrProfileCard(
    groupNostrProfile: GroupNostrProfile,
    publicKey: HexKey
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = {
                ProfileAvatar(
                    publicKey = publicKey,
                    picture = groupNostrProfile.picture(),
                    name = groupNostrProfile.name()
                )
            },
            headlineContent = {
                Text(
                    // Named rather than falling back to the pubkey: the signing key
                    // row above already shows that, and showing it twice would say
                    // the group has a name when it has not.
                    text = groupNostrProfile.name()
                        ?: stringResource(Res.string.no_name_on_this_profile),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    groupNostrProfile.nip05()?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    groupNostrProfile.about()?.let {
                        Text(
                            text = it,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = stringResource(
                            Res.string.signed_by_the_group_on,
                            groupNostrProfile.signedAt.toFormattedTimeAndDateString()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/**
 * Where the group has said it lives on nostr, one line per relay list.
 *
 * One card holding four rows rather than four cards: they are read together -- the
 * question is whether the group is reachable, not what any single list says -- and
 * four cards of one line each on a screen this long is four dividers to scroll past
 * for the same four facts.
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

/**
 * The way into `BroadcastGroupSignedEventScreen`, drawn the same under every
 * signed event in the identity block so that it reads as one action wherever it
 * sits. The icon is decorative because the label is beside it.
 */
@Composable
private fun BroadcastButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.CellTower, contentDescription = Decorative)

        Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))

        Text(stringResource(Res.string.broadcast))
    }
}

/** The name of a relay list, for the summary on the group's screen. */
private fun GroupRelaySet.summaryLabel() = when (this) {
    GroupRelaySet.General -> Res.string.relay_set_general
    GroupRelaySet.Messages -> Res.string.relay_set_messages
    GroupRelaySet.Search -> Res.string.relay_set_search
    GroupRelaySet.Blocked -> Res.string.relay_set_blocked
}

/**
 * One thing the group said, as the group signed it.
 *
 * The whole note rather than a preview. A post is short by construction -- kind:1
 * with no long-form arm behind it -- and truncating the one kind of content on this
 * screen that exists to be *read* would mean opening something to see a paragraph.
 *
 * **The byline is the group's**, taken from its profile where it has one and from
 * the room's own id where it has not. It is worth spelling out because a post is
 * the one thing here a reader might otherwise attribute to whichever member
 * proposed it -- and the point of signing it as the group is that they cannot.
 *
 * A reply is marked as one. Nothing in this app writes replies, so the mark is for
 * an event that arrived some other way; drawing one as an announcement would put
 * half a conversation on a group's page with no sign that it was half.
 */
@Composable
private fun GroupPostCard(
    post: GroupPost,
    publicKey: HexKey,
    groupNostrProfile: GroupNostrProfile?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(
                    size = MaterialTheme.spacing.space400,
                    publicKey = publicKey,
                    picture = groupNostrProfile?.picture(),
                    name = groupNostrProfile?.name()
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.itemGap))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupNostrProfile?.name() ?: publicKey.take(16).inComparableGroups(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = stringResource(
                            Res.string.posted_on,
                            post.postedAt.toFormattedTimeAndDateString()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!post.isNewThread()) {
                    Text(
                        text = stringResource(Res.string.a_reply),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = post.content,
                style = MaterialTheme.typography.bodyMedium
            )
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
 * [onEdit] null draws a card that cannot be opened. It is the section's gate
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
 * One entry the group accepted into a list it curates, as the list's own word.
 *
 * The title first, because every list has one; then which list, since a group
 * may curate several and an entry says nothing about its list on its face;
 * then the glance the queue row gives -- the fields that say what the entry
 * *is*, labelled as the schema labels them, and not the links -- and the body
 * where the list has one. The last line credits the suggester, when there was
 * one, and dates the group's decision, which is the fact a member reading the
 * block came for.
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

/**
 * One subgroup, as the parent's record and this device's rooms together have it.
 *
 * **The title comes from the room where there is one, and only otherwise from the
 * certificate.** The certificate's name and `p` tags are what the parent approved
 * at founding, frozen: the child may since have been renamed or have gained
 * members, and none of that reaches a signature already made. So where this
 * device holds the room, the room is the live answer; where it does not, the
 * certificate is all there is and the supporting line says which of the two
 * absences this is.
 */
@Composable
private fun SubgroupCard(
    subgroup: SubgroupManager.Subgroup,
    onOpen: () -> Unit
) {
    Card(onClick = onOpen) {
        ListItem(
            leadingContent = {
                Icon(Icons.Default.AccountTree, contentDescription = Decorative)
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = Decorative)
            },
            headlineContent = {
                Text(
                    text = subgroup.room?.chatRoom?.subject
                        ?: subgroup.name
                        ?: stringResource(Res.string.a_subgroup)
                )
            },
            supportingContent = {
                Text(
                    text = when {
                        subgroup.isJoined -> stringResource(
                            Res.string.run_by_members,
                            subgroup.adminPublicKeys.size.toString()
                        )
                        // The room does not exist anywhere yet: the parent has
                        // certified it and nobody has created it. Between steps 2
                        // and 4 of docs/subgroups.md this is every member's view.
                        subgroup.keyState == null -> stringResource(Res.string.certified_not_yet_created)
                        else -> stringResource(Res.string.created_you_are_not_a_member)
                    }
                )
            }
        )
    }
}

private fun String.inComparableGroups(): String = chunked(8).joinToString(" ")

/** One labelled value of a key state, whole and comparable. */
@Composable
private fun GroupKeyStateField(
    label: String,
    value: String,
    isMonospaced: Boolean = true
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
        text = value,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (isMonospaced) FontFamily.Monospace else null,
        // Stated rather than inherited: the label above is deliberately quieter,
        // and the value is the thing that was come for.
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(MaterialTheme.spacing.itemGap))
}

/**
 * Reads the room's stored marmot group events again.
 *
 * Sits at the very bottom, below leaving and deleting, because it is a repair
 * rather than something anyone should need in the ordinary course of using the
 * group. It says what it found afterwards -- a replay that recovered nothing
 * looks identical to one that was never pressed, and the difference between
 * "nothing was broken" and "nothing could be fixed" is worth telling.
 */
@Composable
private fun ReindexMarmotGroupEventsButton(
    reindexState: ChatRoomDetailViewModel.ReindexState,
    onReindex: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        TextButton(
            enabled = reindexState !is ChatRoomDetailViewModel.ReindexState.Running,
            onClick = onReindex
        ) {
            if (reindexState is ChatRoomDetailViewModel.ReindexState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = "Reindex group events"
                )
            }

            Spacer(
                modifier = Modifier.width(MaterialTheme.spacing.space125)
            )

            Text(stringResource(Res.string.reindex_events))
        }

        when (reindexState) {
            is ChatRoomDetailViewModel.ReindexState.Done -> {
                val report = reindexState.report
                // What was published before this member joined is named rather
                // than counted as read. Their epoch keys were never on this
                // device, so "all read" would be a claim about messages nobody
                // here can open -- and a room that is mostly older than the
                // member is the ordinary case for anyone invited into one.
                val beforeJoining =
                    if (report.predatingMembership > 0) {
                        " · ${report.predatingMembership} from before you joined"
                    } else {
                        ""
                    }
                Text(
                    text = when {
                        report.isNoOp ->
                            "Nothing to reindex · ${report.stored - report.predatingMembership} " +
                                "event(s) all read$beforeJoining"
                        report.recovered > 0 && report.failed > 0 ->
                            stringResource(Res.string.recovered_of_still_unreadable, report.recovered, report.unresolved, report.failed, beforeJoining)
                        report.recovered > 0 ->
                            stringResource(Res.string.recovered_of_event_s, report.recovered, report.unresolved, beforeJoining)
                        else -> stringResource(Res.string.event_s_still_unreadable, report.failed, beforeJoining)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            is ChatRoomDetailViewModel.ReindexState.Failed -> {
                Text(
                    text = reindexState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            else -> Unit
        }
    }
}

@ConformancePreviews
@Composable
private fun ChatRoomMessagingScreenPreview() {
    CurareTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            ChatRoomDetailScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialChatRoomDetailUIState = ChatRoomDetailUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Message title",
                            description = "Description of a chat room so that members now why they are here.",
                            initialGiftWrapPayloadId = "sdfaer",
                            // A Marmot room, so the nostr profile section renders
                            // at all -- it is hidden in a NIP-17 one, which has no
                            // key to have an identity on nostr.
                            mlsGroupState = "state"
                        ),
                        localParticipants = listOf(
                            LocalParticipant(
                                participant = Participant(
                                    id = 2,
                                    participantPublicKey = "gex",
                                    chatRoomId = "chatRoomId",
                                    relayHint = null
                                ),
                                profile = Profile(
                                    publicKey = "gex",
                                    displayName = "Gexie",
                                    nostrEventId = "nosrtEventId"
                                )
                            )
                        )
                    ),
                    // A room that has one, so the signing key row renders under
                    // all five conditions rather than only in a group that has
                    // held a ceremony. Not a real key: nothing here derives.
                    groupKeyState = GroupKeyState(
                        chatRoomId = "publicKey",
                        dkgSessionId = "c".repeat(64),
                        thresholdPublicKey = "02".padEnd(66, 'a'),
                        derivationPath = "m/9420/0/0",
                        announcedBy = "d".repeat(64),
                        announcedAt = Instant.fromEpochSeconds(1_700_000_000)
                    ),
                    // A group that has described itself, so the section renders
                    // with something in it rather than only in its empty state.
                    // Built directly rather than read out of a signed event: the
                    // reading checks a signature, and there is no real one here.
                    groupNostrProfile = GroupNostrProfile(
                        signedEvent = GroupSignedEvent(
                            id = "e".repeat(64),
                            chatRoomId = "publicKey",
                            publicKey = "publicKey",
                            kind = MetadataEvent.KIND,
                            tags = emptyArray(),
                            content = "",
                            signature = "f".repeat(128),
                            createdAt = Instant.fromEpochSeconds(1_700_000_000)
                        ),
                        metadata = UserMetadata().apply {
                            displayName = "Translation collective"
                            about = "A group translating hard books into Sesotho."
                            nip05 = "group@example.com"
                        }
                    ),
                    canEditNostrProfile = true
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                onNavigateToRoute = {},
                onPopBackToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}