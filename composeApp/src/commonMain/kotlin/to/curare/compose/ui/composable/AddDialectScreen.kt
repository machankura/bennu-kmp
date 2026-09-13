package to.curare.compose.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.FrostSigningRoute
import to.curare.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.AddDialectViewModel
import to.curare.compose.ui.view.state.AddDialectUIState
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.add_a_dialect_the_group_can_translate_into
import curare.composeapp.generated.resources.add_dialect
import curare.composeapp.generated.resources.country
import curare.composeapp.generated.resources.dialect_name
import curare.composeapp.generated.resources.eg_lesotho
import curare.composeapp.generated.resources.eg_sesotho
import curare.composeapp.generated.resources.eg_st
import curare.composeapp.generated.resources.language
import curare.composeapp.generated.resources.propose_dialect
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.buttons.ExtendedFab
import to.curare.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddDialectScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    initialAddDialectUIState: AddDialectUIState = AddDialectUIState.Loading,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val addDialectViewModel: AddDialectViewModel = viewModel(
        factory = AddDialectViewModel.factory(
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialAddDialectUIState = initialAddDialectUIState,
            nostrRepository = nostrRepository,
            chatRepository = chatRepository,
            activeUserPublicKey = activeUserPublicKey,
            frostSigningRepository = frostSigningRepository
        )
    )

    ScreenStateTransition(addDialectViewModel.addDialectUIState) { uiState ->
    when (val addDialectUIState = uiState) {
            is AddDialectUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(
                        modifier = Modifier.height(MaterialTheme.spacing.space600)
                    )
                    Text(
                        text = addDialectUIState.message,
                    )
                }
            }
            is AddDialectUIState.Loaded -> {
                val nameFieldState = rememberTextFieldState()
                val countryFieldState = rememberTextFieldState()
                val languageFieldState = rememberTextFieldState()

                // imePadding: this screen has a text field, and without it the software
                // keyboard covers whatever is being typed into.
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = {
                                addDialectUIState.localChatRoom.RenderChatRoomTitleText()
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_dialect),
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Propose dialect") },
                                    enabled = addDialectUIState.canSign,
                                    onClick = {
                                        addDialectViewModel.addDialect(
                                            localChatRoom = addDialectUIState.localChatRoom,
                                            nameField = nameFieldState,
                                            countryField = countryFieldState,
                                            languageField = languageFieldState,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back to
                                                // the group. Nothing has been created
                                                // yet -- the dialect appears when enough
                                                // members sign -- so landing on the list
                                                // it is not in would read as a failure.
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
                                                    ImplementationPendingRoute("Failed dialect")
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(stringResource(Res.string.add_a_dialect_the_group_can_translate_into))

                            if (!addDialectUIState.canSign) {
                                Text(
                                    text = "This group has no shared key, so it cannot sign a " +
                                        "dialect into existence. Run a shared key ceremony first.",
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
                                        Icons.Default.Title,
                                        contentDescription = "Name of the dialect"
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.dialect_name)
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(Res.string.eg_sesotho)
                                    )
                                },
                            )

                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth()
                                    .background(BottomAppBarDefaults.containerColor),
                                state = countryFieldState,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent
                                ),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Public,
                                        contentDescription = "Country of the dialect"
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.country)
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(Res.string.eg_lesotho)
                                    )
                                },
                            )

                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth()
                                    .background(BottomAppBarDefaults.containerColor),
                                state = languageFieldState,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    disabledBorderColor = Color.Transparent
                                ),
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Translate,
                                        contentDescription = "Language of the dialect"
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(Res.string.language)
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = stringResource(Res.string.eg_st)
                                    )
                                },
                            )
                        }
                    }
                }
            }
            AddDialectUIState.Loading -> {
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
                        text = stringResource(Res.string.add_dialect),
                        style = MaterialTheme.typography.bodyLarge,
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
        if (initialAddDialectUIState == AddDialectUIState.Loading) {
            addDialectViewModel.initiateAddDialect()
        }
    }
}

@ConformancePreviews
@Composable
private fun AddDialectScreenPreview() {
    CurareTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            AddDialectScreen(
                activeUserPublicKey = "",
                chatRoomId = "publicKey",
                relayHint = null,
                initialAddDialectUIState = AddDialectUIState.Loaded(
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
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {},
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
