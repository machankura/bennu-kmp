package to.curare.compose.ui.composable.widgets.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalNostrEvent
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import to.curare.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.profile.ProfileAvatar
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.MetadataEventDetailType
import to.curare.compose.ui.view.model.MetadataEventDetailViewModel
import to.curare.compose.ui.view.state.MetadataEventDetailUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.add_to_group
import curare.composeapp.generated.resources.block_user
import curare.composeapp.generated.resources.edit_profile
import curare.composeapp.generated.resources.follow
import curare.composeapp.generated.resources.follow_back
import curare.composeapp.generated.resources.send_message
import curare.composeapp.generated.resources.unfollow
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MetadataEventDetail(
    localNostrEvent: LocalNostrEvent,
    activeUserPublicKey: HexKey,
    onNavigateBack: () -> Unit,
    onNavigateToEvent: (Route) -> Unit,
    onNavigateToEditProfile: () -> Unit,
    onNavigateToChatRoom: (Route) -> Unit,
    nostrRepository: NostrRepository
) {
    val scope = rememberCoroutineScope()

    val profile = localNostrEvent.profile
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    val metadataEventDetailViewModel: MetadataEventDetailViewModel = viewModel(
        factory = MetadataEventDetailViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            publicKey = localNostrEvent.nostrEvent.pubKey,
            nostrRepository = nostrRepository,
            pagerState = rememberPagerState { MetadataEventDetailType.entries.size }
        ),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack
                    ) {
                        Icon(
                            Icons.Default.ArrowBackIosNew,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                title = {
                    Column(
                        modifier = Modifier,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = profile?.displayName ?: profile?.userName ?: localNostrEvent.nostrEvent.pubKey,
                            style = MaterialTheme.typography.titleLarge
                        )

                        profile?.nip05?.let {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                            ) {
                                Icon(
                                    Icons.Default.Bolt,
                                    contentDescription = "Lightning payment",
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                        }

                    }
                },

                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
        ) {

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProfileAvatar(
                                size = 75.dp,
                                profile = profile,
                                publicKey = localNostrEvent.nostrEvent.pubKey
                            )


                            Spacer(
                                modifier = Modifier.weight(1f)
                            )

                            when(val metadataEventDetailUIState = metadataEventDetailViewModel.metadataEventDetailUIState) {
                                MetadataEventDetailUIState.Error -> {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = "Something went wrong"
                                    )
                                }
                                is MetadataEventDetailUIState.Loaded -> {

                                    if (metadataEventDetailViewModel.isActionPending.value) {
                                        LoadingIndicator()
                                    } else {
                                        if (metadataEventDetailUIState.isActiveUser) {
                                            Button(
                                                onClick = onNavigateToEditProfile
                                            ) {
                                                Text(
                                                    stringResource(Res.string.edit_profile)
                                                )
                                            }

                                        } else if (metadataEventDetailUIState.followedByActiveUserConnection != null) {
                                            Button(
                                                onClick = {
                                                    // Unfollow
                                                    metadataEventDetailViewModel.unfollow(
                                                        metadataEventDetailUIState.followedByActiveUserConnection
                                                    )
                                                }
                                            ) {
                                                Text(
                                                    stringResource(Res.string.unfollow)
                                                )
                                            }
                                        } else {
                                            // flow
                                            Button(
                                                onClick = {
                                                    metadataEventDetailViewModel.follow()
                                                }
                                            ) {
                                                Text(
                                                    text = if (metadataEventDetailUIState.followingActiveUserConnection != null) {
                                                        stringResource(Res.string.follow_back)
                                                    } else {
                                                        stringResource(Res.string.follow)
                                                    }
                                                )
                                            }
                                        }
                                    }


                                }
                                MetadataEventDetailUIState.Loading -> {
                                    LoadingIndicator()
                                }
                            }
                        }
                        Text(
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = MaterialTheme.spacing.space250
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            text = profile?.about ?: ""
                        )
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            onNavigateToEvent.invoke(
                                ImplementationPendingRoute("Add to group")
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.GroupAdd,
                            contentDescription = "Add to group"
                        )

                        Spacer(
                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                        )

                        Text(stringResource(Res.string.add_to_group))
                    }
                }

                item {
                    TextButton(
                        onClick = {
                            onNavigateToEvent.invoke(
                                ImplementationPendingRoute("Block user")
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = "Block"
                        )

                        Spacer(
                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                        )

                        Text(stringResource(Res.string.block_user))
                    }
                }

                item {
                    Button(
                        onClick = {
                            onNavigateToChatRoom.invoke(
                                ChatRoomMessagingRoute(
                                    activeUserPublicKey = activeUserPublicKey,
                                    chatRoomId = localNostrEvent.nostrEvent.pubKey,
                                    relayHint = localNostrEvent.nostrEvent.relayUrl,
                                )
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.Mail,
                            contentDescription = "Direct message"
                        )

                        Spacer(
                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                        )
                        Text(stringResource(Res.string.send_message))
                    }
                }
            }

            key(true) {
                metadataEventDetailViewModel.initiate()
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun MetadataEventEventDetailPreview() {

    CurareTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            MetadataEventDetail(
                activeUserPublicKey = "",
                localNostrEvent = LocalNostrEvent(
                    nostrEvent = NostrEvent(
                        id = "eventId",
                        pubKey = "publicKey",
                        content = """Profile""".trimIndent(),
                        tags = emptyArray(),
                        sig = "",
                        kind = MetadataEvent.KIND
                    ),
                    profile = Profile(
                        publicKey = "pubKey",
                        displayName = "John Doe",
                        nip05 = "john@doe.com",
                        about = "I am the stone that the builder refused.",
                        nostrEventId = "nostrEventId"
                    )
                ),
                onNavigateBack = {},
                onNavigateToEvent = {},
                onNavigateToEditProfile = {},
                onNavigateToChatRoom = {},
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY
            )
        }
    }
}