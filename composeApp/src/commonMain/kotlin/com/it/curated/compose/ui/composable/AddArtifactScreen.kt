package com.it.curated.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.it.curated.compose.database.model.ChatRoom
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.FrostSigningRepository
import com.it.curated.compose.repository.NostrRepository
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.composable.widgets.LoadingDataIndicator
import com.it.curated.compose.ui.theme.TorchTheme
import com.it.curated.compose.ui.view.state.ChatRoomMessagingUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.repository.MantraRepository
import com.it.curated.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.it.curated.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.it.curated.compose.ui.view.model.AddArtifactViewModel
import com.it.curated.compose.ui.view.state.AddArtifactUIState
import com.it.curated.compose.ui.theme.spacing
import curated.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curated.composeapp.generated.resources.add_artifact_to_the_group_library
import curated.composeapp.generated.resources.direct_message_detail
import curated.composeapp.generated.resources.direct_message_functionality_will_be_here
import curated.composeapp.generated.resources.eg_first_edition
import curated.composeapp.generated.resources.eg_https_harper_com_2_kill_bird
import curated.composeapp.generated.resources.eg_to_kill_a_mocking_bird
import curated.composeapp.generated.resources.initial_version_label
import curated.composeapp.generated.resources.name_of_artifact
import curated.composeapp.generated.resources.no_dialects_have_been_defined_in_this_group
import curated.composeapp.generated.resources.propose_artifact
import curated.composeapp.generated.resources.source_dialect
import curated.composeapp.generated.resources.url
import androidx.compose.material3.SnackbarHost
import com.it.curated.compose.ui.composable.widgets.LocalSnackbarHostState
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.composable.widgets.ScreenStateTransition
import com.it.curated.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddArtifactScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialAddArtifactUIState: AddArtifactUIState = AddArtifactUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    mantraRepository: MantraRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateToRoute: (Route) -> Unit,
) {
    val addArtifactViewModel: AddArtifactViewModel = viewModel(
        factory = AddArtifactViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialAddArtifactUIState = initialAddArtifactUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            activeUserPublicKey = activeUserPublicKey,
            mantraRepository = mantraRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    ScreenStateTransition(addArtifactViewModel.addArtifactUIState) { uiState ->
    when (val addArtifactUIState = uiState) {
            is AddArtifactUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = addArtifactUIState.message,
                    )
                }
            }
            is AddArtifactUIState.Loaded -> {
                val nameFieldState = rememberTextFieldState()
                val urlFieldState = rememberTextFieldState()
                val versionLabelFieldState = rememberTextFieldState("1.0")

                // The id of the group dialect this artifact is written in. Null until
                // one is picked; dialects are defined from the group detail screen.
                var selectedDialectId: String? by remember { mutableStateOf(null) }

                // The two things the form cannot ask for again. A dialect has to
                // already exist and nothing here can create one; a shared key has to
                // have been ceremonied and this is not where that happens. Either
                // missing is a dead end rather than something to propose and be told
                // about afterwards, and the button says so.
                val canAddArtifact = selectedDialectId != null && addArtifactUIState.canSign

                // M3 gives a FAB no `enabled`, so borrow the disabled colours every
                // other button in the app uses rather than inventing a shade here.
                val buttonColors = ButtonDefaults.buttonColors()

                // imePadding: this screen has a text field, and without it the software
                // keyboard covers whatever is being typed into.
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                addArtifactUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            actions = {

                            }
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFloatingActionButton(
                                    modifier = if (canAddArtifact) {
                                        Modifier
                                    } else {
                                        // Looking unavailable is not being unavailable:
                                        // without this a screen reader still announces
                                        // a button it is happy to press.
                                        Modifier.semantics { disabled() }
                                    },
                                    containerColor = if (canAddArtifact) {
                                        FloatingActionButtonDefaults.containerColor
                                    } else {
                                        buttonColors.disabledContainerColor
                                    },
                                    contentColor = if (canAddArtifact) {
                                        contentColorFor(FloatingActionButtonDefaults.containerColor)
                                    } else {
                                        buttonColors.disabledContentColor
                                    },
                                    onClick = {
                                        if (!canAddArtifact) return@ExtendedFloatingActionButton

                                        addArtifactViewModel.addArtifact(
                                            localChatRoom = addArtifactUIState.localChatRoom,
                                            nameField = nameFieldState,
                                            urlField = urlFieldState,
                                            versionLabelField = versionLabelFieldState,
                                            dialectId = selectedDialectId,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than to the
                                                // artifact. Nothing has been created yet
                                                // -- the artifact appears when enough
                                                // members sign -- so a detail screen for
                                                // a row that does not exist would read as
                                                // a failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            onFailure = {
                                                onNavigateToRoute.invoke(
                                                    ImplementationPendingRoute("Failed artifact")
                                                )
                                            }
                                        )

                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Propose artifact"
                                    )
                                    Text(stringResource(Res.string.propose_artifact))
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        // TODO: Check that we have direct message relays for this user...
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(Res.string.add_artifact_to_the_group_library))

                            if (!addArtifactUIState.canSign) {
                                Text(
                                    text = "This group has no shared key, so it cannot sign an " +
                                        "artifact into its library. Run a shared key ceremony first.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth()
                                    .background(BottomAppBarDefaults.containerColor),
                                state = nameFieldState,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent
                                ),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DriveFileRenameOutline,
                                        contentDescription = "Name of the artifact"
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.name_of_artifact)
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(Res.string.eg_to_kill_a_mocking_bird)
                                    )
                                },
                            )

                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth()
                                    .background(BottomAppBarDefaults.containerColor),
                                state = urlFieldState,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent
                                ),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Link to the artifact"
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.url)
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(Res.string.eg_https_harper_com_2_kill_bird)
                                    )
                                },
                            )

                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth()
                                    .background(BottomAppBarDefaults.containerColor),
                                state = versionLabelFieldState,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent
                                ),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.LocalOffer,
                                        contentDescription = "Verison label"
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.initial_version_label)
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(Res.string.eg_first_edition)
                                    )
                                },
                            )

                            Text(stringResource(Res.string.source_dialect))

                            if (addArtifactUIState.dialects.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.no_dialects_have_been_defined_in_this_group),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                                ) {
                                    addArtifactUIState.dialects.forEach { dialect ->
                                        FilterChip(
                                            selected = selectedDialectId == dialect.id,
                                            onClick = { selectedDialectId = dialect.id },
                                            label = { Text(dialect.name) }
                                        )
                                    }
                                }
                            }

                            // TODO: Add Visibility
                        }
                    }
                }
            }
            AddArtifactUIState.Loading -> {
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
        if (initialAddArtifactUIState == AddArtifactUIState.Loading) {
            addArtifactViewModel.initiateChatRoomDetail()
        }
    }
}

@ConformancePreviews
@Composable
private fun AddArtifactScreenPreview() {
    TorchTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            AddArtifactScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialAddArtifactUIState = AddArtifactUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Message title",
                            description = "See something. Say somethin",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        ),
                    )
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {},
                onNavigateToRoute = {}
            )
        }
    }
}