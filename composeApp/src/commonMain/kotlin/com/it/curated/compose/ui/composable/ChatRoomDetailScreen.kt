package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Translate
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
import com.it.curated.compose.database.model.ChatRoom
import com.it.curated.compose.database.model.GroupKeyState
import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.database.model.Participant
import com.it.curated.compose.database.model.Profile
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.database.model.intermdiate.LocalParticipant
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.MantraRepository
import com.it.curated.compose.repository.NostrRepository
import com.it.curated.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.composable.navigation.routes.DkgRitualRoute
import com.it.curated.compose.ui.composable.navigation.routes.ProposalListRoute
import com.it.curated.compose.ui.composable.navigation.routes.SearchMemberToAddToChatRoomRoute
import com.it.curated.compose.extensions.toFormattedTimeAndDateString
import com.it.curated.compose.ui.composable.widgets.Decorative
import com.it.curated.compose.ui.composable.widgets.LoadingDataIndicator
import com.it.curated.compose.ui.composable.widgets.dialogs.ModalBottomSheet
import com.it.curated.compose.ui.composable.widgets.rememberNotifier
import com.it.curated.compose.ui.composable.widgets.profile.ProfileAvatar
import com.it.curated.compose.ui.theme.TorchTheme
import com.it.curated.compose.ui.view.model.ChatRoomDetailViewModel
import com.it.curated.compose.ui.view.state.ChatRoomDetailUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.ui.composable.navigation.routes.AddArtifactRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddDialectRoute
import com.it.curated.compose.ui.composable.navigation.routes.ArtifactDetailRoute
import com.it.curated.compose.ui.theme.spacing
import curated.composeapp.generated.resources.run_by_members
import curated.composeapp.generated.resources.parent_group
import curated.composeapp.generated.resources.a_subgroup
import curated.composeapp.generated.resources.created_you_are_not_a_member
import curated.composeapp.generated.resources.certified_not_yet_created
import curated.composeapp.generated.resources.no_subgroups_have_been_made_by_this_group
import curated.composeapp.generated.resources.add_subgroup
import curated.composeapp.generated.resources.subgroups
import com.it.curated.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import com.it.curated.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.it.curated.compose.managers.SubgroupManager
import curated.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curated.composeapp.generated.resources.add_artifact_to_library
import curated.composeapp.generated.resources.add_dialect
import curated.composeapp.generated.resources.create_project
import curated.composeapp.generated.resources.delete_group
import curated.composeapp.generated.resources.dialects
import curated.composeapp.generated.resources.direct_message_detail
import curated.composeapp.generated.resources.direct_message_functionality_will_be_here
import curated.composeapp.generated.resources.invite_new_member
import curated.composeapp.generated.resources.leave_group
import curated.composeapp.generated.resources.library
import curated.composeapp.generated.resources.members_2
import curated.composeapp.generated.resources.no_artifacts_exists_in_this_groups_library
import curated.composeapp.generated.resources.no_dialects_have_been_defined_in_this_group_2
import curated.composeapp.generated.resources.no_projects_exists_in_this_group
import curated.composeapp.generated.resources.projects
import curated.composeapp.generated.resources.proposals
import curated.composeapp.generated.resources.reindex_events
import curated.composeapp.generated.resources.shared_key
import curated.composeapp.generated.resources.signing_key
import curated.composeapp.generated.resources.the_key_this_room_signs_as
import curated.composeapp.generated.resources.what_the_group_signed
import curated.composeapp.generated.resources.this_room_signs_as
import curated.composeapp.generated.resources.derivation_path
import curated.composeapp.generated.resources.key_ceremony
import curated.composeapp.generated.resources.agreed_on
import curated.composeapp.generated.resources.the_signed_event
import curated.composeapp.generated.resources.copy_the_signed_event
import curated.composeapp.generated.resources.copied_the_signed_event
import curated.composeapp.generated.resources.this_device_does_not_hold_the_event
import curated.composeapp.generated.resources.event_s_still_unreadable
import curated.composeapp.generated.resources.recovered_of_event_s
import curated.composeapp.generated.resources.recovered_of_still_unreadable
import androidx.compose.material3.SnackbarHost
import com.it.curated.compose.ui.composable.widgets.LocalSnackbarHostState
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.composable.widgets.ScreenStateTransition
import com.it.curated.compose.ui.theme.ConformancePreviews
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
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
    onPopBackToRoute: (Route) -> Unit
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
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        var isShowingGroupKeyState by rememberSaveable { mutableStateOf(false) }

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

                            item {
                                // Artifacts
                                Text(
                                    text = stringResource(Res.string.library),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            if (chatRoomDetailUIState.artifacts.isEmpty()) {
                                item {
                                    Text(stringResource(Res.string.no_artifacts_exists_in_this_groups_library))
                                }
                            } else {
                                items(
                                    items = chatRoomDetailUIState.artifacts,
                                    key = { artifact -> artifact.id }
                                ) { artifact ->
                                    Card(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ArtifactDetailRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    artifactId = artifact.id,
                                                    chatRoomId = chatRoomId,
                                                    relayHint = relayHint
                                                )
                                            )
                                        }
                                    ) {
                                        ListItem(
                                            leadingContent = {
                                                Icon(
                                                    Icons.Default.LibraryBooks,
                                                    contentDescription = "Artifact"
                                                )
                                            },
                                            trailingContent = {
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = "View artifact"
                                                )
                                            },
                                            headlineContent = {
                                                Text(text = artifact.name)
                                            },
                                            supportingContent = {
                                                Text(text = artifact.url)
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        // Navigate to the invite user screen...
                                        onNavigateToRoute.invoke(
                                            AddArtifactRoute(
                                                chatRoomId = chatRoomId,
                                                activeUserPublicKey = activeUserPublicKey,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.LibraryBooks,
                                        contentDescription = "Add new artifact"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.add_artifact_to_library))
                                }
                            }

                            item {
                                HorizontalDivider()
                            }

                            item {
                                // Dialects
                                Text(
                                    text = stringResource(Res.string.dialects),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            if (chatRoomDetailUIState.dialects.isEmpty()) {
                                item {
                                    Text(stringResource(Res.string.no_dialects_have_been_defined_in_this_group_2))
                                }
                            } else {
                                items(
                                    items = chatRoomDetailUIState.dialects,
                                    key = { dialect -> dialect.id }
                                ) { dialect ->
                                    Card {
                                        ListItem(
                                            leadingContent = {
                                                Icon(
                                                    Icons.Default.Translate,
                                                    contentDescription = "Dialect"
                                                )
                                            },
                                            headlineContent = {
                                                Text(text = dialect.name)
                                            },
                                            supportingContent = {
                                                Text(text = "${dialect.language} \u00b7 ${dialect.country}")
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            AddDialectRoute(
                                                chatRoomId = chatRoomId,
                                                activeUserPublicKey = activeUserPublicKey,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.Translate,
                                        contentDescription = "Add new dialect"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.add_dialect))
                                }
                            }

                            item {
                                HorizontalDivider()
                            }

                            // TODO: Add projects...

                            item {
                                // Artifacts
                                Text(
                                    text = stringResource(Res.string.projects),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            item {
                                Text(stringResource(Res.string.no_projects_exists_in_this_group))
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        // Navigate to the invite user screen...
                                        onNavigateToRoute.invoke(
                                            ImplementationPendingRoute(
                                                "Add new project"
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.Schema,
                                        contentDescription = "create new projec"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.create_project))
                                }
                            }

                            item {
                                HorizontalDivider()
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
    TorchTheme {
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
                            mlsGroupState = null
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
                    )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                onNavigateToRoute = {},
                onPopBackToRoute = {}
            )
        }
    }
}