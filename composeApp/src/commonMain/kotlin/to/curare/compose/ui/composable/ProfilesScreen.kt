package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.loading_profiles
import curare.composeapp.generated.resources.profiles
import fr.acinq.phoenix.data.WalletId
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.ui.composable.widgets.ErrorState
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.composable.widgets.wallet.WalletsSelector
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.view.model.ProfilesViewModel
import to.curare.compose.ui.view.state.ProfilesUIState

/**
 * The profiles on this device -- the one that is open above a divider, the others
 * below -- and the way from one to another. Phase 4 of docs/multiple-profiles.md.
 *
 * Tapping another row is [onSwitch] and nothing else: the navigation observer restarts
 * the app as that identity, through its lock gate, so this screen is never around to
 * see the result. Tapping the current row does nothing. A pushed screen behind one
 * deliberate tap from the profile tab, and never a global control, because a switch
 * clears the back stack from wherever the user is.
 *
 * One column at every width: a list of a handful of rows has no detail to pair with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel,
    onNavigateBack: () -> Unit,
    onSwitch: (WalletId) -> Unit,
    /** Re-lists. An unreadable key file is exactly the failure a retry can answer. */
    onRetry: () -> Unit,
    /** The two ways to add a profile, drawn under the list; Phase 5 fills the slot. */
    addProfile: (@Composable () -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.profiles)) },
                navigationIcon = { NavigateBackButton(onNavigateBack) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .readableContent(),
        ) {
            ScreenStateTransition(uiState) { state ->
                when (state) {
                    is ProfilesUIState.Loading -> LoadingDataIndicator(
                        text = stringResource(Res.string.loading_profiles),
                    )
                    is ProfilesUIState.Error -> ErrorState(onRetry = onRetry)
                    is ProfilesUIState.Loaded -> WalletsSelector(
                        modifier = Modifier.fillMaxSize().padding(horizontal = MaterialTheme.spacing.screenMargin),
                        wallets = state.identities,
                        walletsMetadata = state.walletsMetadata,
                        activeWalletId = state.activeId,
                        canEdit = false,
                        onWalletClick = { onSwitch(it.id) },
                        profiles = state.profiles,
                        bottomContent = addProfile,
                    )
                }
            }
        }
    }
}
