package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.looking_for_this_profile_on_the_relays
import curare.composeapp.generated.resources.profile
import curare.composeapp.generated.resources.start_new_chat
import curare.composeapp.generated.resources.try_again
import curare.composeapp.generated.resources.we_could_not_find_a_profile_for_this_key
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.database.model.Profile
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.EmptyState
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalCanSign
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.profile.ProfileAvatar
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.ProfilePreviewViewModel
import to.curare.compose.ui.view.state.ProfilePreviewUIState

/**
 * The person behind a pasted npub, and the one thing to do about them.
 *
 * Reached from the new-chat dialog by public key, before anything is created: the
 * profile block is the confirmation the flow never had, and "Start new chat" hands
 * over to `ChatRoomMessagingRoute` exactly as the dialog used to -- the room is made
 * where it has always been made, and this screen is popped under it, as the profile
 * detail is under a chat started from it. See docs/npub-profile-preview.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilePreviewScreen(
    activeUserPublicKey: HexKey,
    profilePublicKey: HexKey,
    initialProfilePreviewUIState: ProfilePreviewUIState = ProfilePreviewUIState.Loading,
    nostrRepository: NostrRepository,
    onStartChat: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val profilePreviewViewModel: ProfilePreviewViewModel = viewModel(
        factory = ProfilePreviewViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            profilePublicKey = profilePublicKey,
            initialProfilePreviewUIState = initialProfilePreviewUIState,
            nostrRepository = nostrRepository,
        ),
    )

    // Sixty-three characters of bech32, remembered once: they are what the user
    // pasted, and the block is not a confirmation without them.
    val npub = remember(profilePublicKey) { profilePublicKey.hexToNpubHrp() }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.profile)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { innerPadding ->
        // The bar is constant, so the transition wraps the content alone. None of the
        // branches needs `ColumnScope`, which is the one thing that costs.
        ScreenStateTransition(profilePreviewViewModel.profilePreviewUIState) { uiState ->
            when (val state = uiState) {
                ProfilePreviewUIState.Loading -> {
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                    ) {
                        Npub(npub)
                        LoadingDataIndicator(
                            fillScreen = false,
                            text = stringResource(Res.string.looking_for_this_profile_on_the_relays),
                        )
                    }
                }

                is ProfilePreviewUIState.Loaded -> {
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacing.screenMargin),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sectionGap),
                    ) {
                        ProfileBlock(profile = state.profile, publicKey = profilePublicKey, npub = npub)

                        // A message is a gift wrap, sealed with the sender's key. Not for a
                        // read-only identity, which is shown the person and nothing to do.
                        if (LocalCanSign.current) {
                            StartChatButton(
                                onClick = {
                                    onStartChat(
                                        ChatRoomMessagingRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            chatRoomId = profilePublicKey,
                                            relayHint = null,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }

                ProfilePreviewUIState.NotFound -> {
                    // Nothing went wrong: the search came back empty, and that is the one
                    // absence this screen has to name. Asking again is the action.
                    EmptyState(
                        modifier = Modifier.padding(innerPadding).readableContent(),
                        message = stringResource(Res.string.we_could_not_find_a_profile_for_this_key),
                        icon = Icons.Default.PersonSearch,
                        action = {
                            TextButton(onClick = { profilePreviewViewModel.retry() }) {
                                Text(stringResource(Res.string.try_again))
                            }
                        },
                    )
                }

                ProfilePreviewUIState.Error -> {
                    // The queue refused, so nothing was asked; asking again is the retry.
                    ErrorState(
                        modifier = Modifier.padding(innerPadding).readableContent(),
                        onRetry = { profilePreviewViewModel.retry() },
                    )
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialProfilePreviewUIState == ProfilePreviewUIState.Loading) {
            profilePreviewViewModel.initiate()
        }
    }
}

/**
 * Who this is: leading-aligned, because rows with an avatar align to a leading edge
 * and centring is for a block that is the only thing on the screen, which this is
 * not once the button is under it.
 */
@Composable
private fun ProfileBlock(
    profile: Profile,
    publicKey: HexKey,
    npub: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        // 75dp is the avatar's size, a dimension and not a spacing, and is what the
        // profile detail draws.
        ProfileAvatar(
            size = 75.dp,
            publicKey = publicKey,
            profile = profile,
        )

        Text(
            text = profile.humanReadableNameOrPubkey(),
            style = MaterialTheme.typography.titleLarge,
        )

        // The nip05 or the lightning address: the thing a user compares against what
        // they were told.
        profile.staticIdentifier()?.let { staticIdentifier ->
            Text(
                text = staticIdentifier,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The whole of it. This is the one screen whose job is to let the user read it.
        profile.about?.takeIf { it.isNotBlank() }?.let { about ->
            Text(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Npub(npub)
    }
}

@Composable
private fun Npub(npub: String) {
    Text(
        text = npub,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A `Button`, not the bottom-bar `ExtendedFloatingActionButton` the invite
 * confirmation uses: Phase 4 of the plan disables this with a reason beside it, and a
 * FAB has no disabled state in M3 or in the API.
 */
@Composable
private fun StartChatButton(onClick: () -> Unit) {
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Icon(
            Icons.Default.Mail,
            // The label is beside it.
            contentDescription = Decorative,
        )
        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
        Text(stringResource(Res.string.start_new_chat))
    }
}

@ConformancePreviews
@Composable
private fun ProfilePreviewScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProfilePreviewScreen(
                activeUserPublicKey = "a".repeat(64),
                profilePublicKey = "b".repeat(64),
                initialProfilePreviewUIState = ProfilePreviewUIState.Loaded(
                    profile = Profile(
                        publicKey = "b".repeat(64),
                        displayName = "John Doe",
                        nip05 = "john@doe.com",
                        about = "I am the stone that the builder refused.",
                        nostrEventId = "nostrEventId",
                    ),
                ),
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                onStartChat = {},
                onNavigateBack = {},
            )
        }
    }
}
