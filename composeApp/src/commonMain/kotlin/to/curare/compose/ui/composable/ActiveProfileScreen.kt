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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bento
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.intermdiate.LocalNostrEvent
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import to.curare.compose.ui.composable.navigation.routes.KeyPackageManagementRoute
import to.curare.compose.ui.composable.navigation.routes.KeyRecoveryRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.navigation.routes.ShareProfileRoute
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.profile.ProfileAvatar
import to.curare.compose.ui.view.model.ActiveProfileViewModel
import to.curare.compose.ui.view.state.ActiveProfileUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.change_account
import curare.composeapp.generated.resources.edit_profile
import curare.composeapp.generated.resources.key_package_management
import curare.composeapp.generated.resources.network_relays
import curare.composeapp.generated.resources.profile
import curare.composeapp.generated.resources.key_recovery
import curare.composeapp.generated.resources.share_profile
import curare.composeapp.generated.resources.sign_out
import curare.composeapp.generated.resources.something_went_wrong
import curare.composeapp.generated.resources.we_couldn_t_find_the_local_profile_please
import to.curare.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActiveProfileScreen(
    initialActiveProfileUIState: ActiveProfileUIState = ActiveProfileUIState.Loading,
    activeUserPublicKey: HexKey,
    nostrEventId: HexKey,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    nostrRepository: NostrRepository,
) {

    val activeProfileViewModel: ActiveProfileViewModel = viewModel(
        factory = ActiveProfileViewModel.factory(
            nostrEventId = nostrEventId,
            initialActiveProfileUIState = initialActiveProfileUIState,
            nostrRepository = nostrRepository,
            activeUserPublicKey = activeUserPublicKey
        ),
    )

    ScreenStateTransition(activeProfileViewModel.activeProfileUIState) { uiState ->
    when (val activeProfileUIState = uiState) {
            ActiveProfileUIState.Error -> {
                ErrorState()
            }
            is ActiveProfileUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(Res.string.profile)
                                )
                            },
                            navigationIcon = { NavigateBackButton(onNavigateBack) }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding)
                        .readableContent()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                                ) {
                                    ProfileAvatar(
                                        profile = activeProfileUIState.localNostrEvent.profile,
                                        publicKey = activeUserPublicKey
                                    )

                                    Column {
                                        activeProfileUIState.localNostrEvent.profile?.humanReadableNameOrPubkey()?.let { humanReadableNameOrPubkey ->
                                            Text(
                                                text = humanReadableNameOrPubkey,
                                                style = MaterialTheme.typography.titleLarge
                                            )
                                        }
                                        activeProfileUIState.localNostrEvent.profile?.about?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }

                                        Spacer(
                                            modifier = Modifier.height(MaterialTheme.spacing.space125)
                                        )

                                        Text(
                                            text = activeUserPublicKey.hexToNpubHrp(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125)
                                ) {
                                    Spacer(
                                        modifier = Modifier.weight(1f)
                                    )

                                    TextButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ImplementationPendingRoute("Edit profile")
                                            )
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit profile"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )

                                        Text(
                                            stringResource(Res.string.edit_profile)
                                        )
                                    }

                                    Spacer(
                                        modifier = Modifier.weight(1f)
                                    )

                                    // The list this sits in is otherwise TextButtons --
                                    // edit profile, key packages, change account, profile
                                    // keys, network relays. Two of the seven were filled
                                    // Buttons, which is M3's highest emphasis and is meant
                                    // for one action per screen. Sharing is the useful one,
                                    // so it keeps medium emphasis rather than maximum.
                                    FilledTonalButton(
                                        onClick = {
                                            onNavigateToRoute.invoke(
                                                ShareProfileRoute(
                                                    activeUserPublicKey = activeUserPublicKey,
                                                    nostrEventId = nostrEventId
                                                )
                                            )
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.QrCode,
                                            contentDescription = "QR code"
                                        )

                                        Spacer(
                                            modifier = Modifier.width(MaterialTheme.spacing.space125)
                                        )
                                        Text(
                                            stringResource(Res.string.share_profile)
                                        )
                                    }

                                    Spacer(
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            KeyPackageManagementRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                nostrEventId = nostrEventId
                                            )
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.Bento,
                                        contentDescription = "Key packages"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.key_package_management)
                                    )
                                }
                            }


                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ImplementationPendingRoute("Change account")
                                        )
                                    }
                                ) {

                                    Icon(
                                        Icons.Default.ImportExport,
                                        contentDescription = "Change profile"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.change_account)
                                    )
                                }
                            }


                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            KeyRecoveryRoute(
                                                activeUserPublicKey = activeUserPublicKey
                                            )
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = Decorative
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(stringResource(Res.string.key_recovery))
                                }
                            }

                            item {
                                TextButton(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ImplementationPendingRoute("Network relays")
                                        )

                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Hub,
                                        contentDescription = "Network relays"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.network_relays)
                                    )
                                }
                            }

                            item {
                                // Signing out was the *other* filled Button -- the most
                                // prominent control on the screen given to its most
                                // destructive action. It now matches how leaving and
                                // deleting a group are already treated in
                                // ChatRoomDetailScreen: a TextButton in the error colour.
                                TextButton(
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ImplementationPendingRoute("Sign out")
                                        )
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Logout,
                                        contentDescription = "Logout"
                                    )

                                    Spacer(
                                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                                    )

                                    Text(
                                        stringResource(Res.string.sign_out)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            ActiveProfileUIState.Loading -> {
                LoadingDataIndicator()

                LaunchedEffect(true) {
                    activeProfileViewModel.loadNostrFeed()
                }
            }
            ActiveProfileUIState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(Res.string.we_couldn_t_find_the_local_profile_please))
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun UnannouncedProfileScreenPreview() {
    to.curare.compose.ui.theme.CurareTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            ActiveProfileScreen(
                initialActiveProfileUIState = ActiveProfileUIState.Loaded(
                    localNostrEvent = LocalNostrEvent(
                        nostrEvent = NostrEvent(
                            id = "eventId",
                            pubKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                            content = """Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
                                
Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. 
                                
It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.""".trimIndent(),
                            tags = emptyArray(),
                            sig = "",
                            kind = MetadataEvent.KIND
                        ),
                        profile = to.curare.compose.database.model.Profile(
                            publicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                            displayName = "John Doe",
                            nostrEventId = "nostrEventId",
                            about = "I been here, there and everywhere."
                        )

                    )
                ),
                activeUserPublicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                nostrEventId = "",
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {}
            )
        }
    }
}