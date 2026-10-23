package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.address_copied
import bennu.composeapp.generated.resources.addresses
import bennu.composeapp.generated.resources.addresses_explainer
import bennu.composeapp.generated.resources.being_handed_out_now
import bennu.composeapp.generated.resources.copy_address_s
import bennu.composeapp.generated.resources.finding_the_group_s_coins
import bennu.composeapp.generated.resources.not_used_yet
import bennu.composeapp.generated.resources.paid_s_sats
import bennu.composeapp.generated.resources.paid_spent_since
import bennu.composeapp.generated.resources.this_profile_cannot_watch_the_chain
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.text.grouped
import com.machankura.bennu.compose.ui.composable.widgets.EmptyState
import com.machankura.bennu.compose.ui.composable.widgets.ErrorState
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.rememberNotifier
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.theme.spacing
import com.machankura.bennu.compose.ui.view.model.GroupWalletAddressesViewModel
import com.machankura.bennu.compose.ui.view.state.GroupWalletAddressesUIState
import com.machankura.bennu.compose.wallet.GroupAddress
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import org.jetbrains.compose.resources.stringResource

/**
 * The group wallet's receive addresses, newest first: every one paid, the one
 * the wallet screen is handing out, and the few after it.
 *
 * Modelled on Phoenix's swap-in address list, with the look-ahead cut to what is
 * worth handing out. The addresses after the current one are there so that two
 * members sharing the wallet at once can each give a payer a different one;
 * the sentence above the list says so, or nobody would use anything but the top.
 *
 * No block explorer: a tap that sends every address of the group's wallet to a
 * third party's server is the leak the watch-only export warns about, made
 * without asking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWalletAddressesScreen(
    chatRoomId: String,
    chain: Chain,
    initialUIState: GroupWalletAddressesUIState = GroupWalletAddressesUIState.Loading,
    chatRepository: ChatRepository,
    wallets: GroupOnchainWallets?,
    onNavigateBack: () -> Unit,
) {
    val addressesViewModel: GroupWalletAddressesViewModel = viewModel(
        factory = GroupWalletAddressesViewModel.factory(
            chatRoomId = chatRoomId,
            chain = chain,
            initialUIState = initialUIState,
            chatRepository = chatRepository,
            wallets = wallets,
        )
    )

    LaunchedEffect(true) {
        if (initialUIState == GroupWalletAddressesUIState.Loading) {
            addressesViewModel.initiate()
        }
    }

    val notify = rememberNotifier(rememberCoroutineScope())

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.addresses),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(addressesViewModel.uiState) { uiState ->
            when (val state = uiState) {
                is GroupWalletAddressesUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupWalletAddressesUIState.Loading -> {
                    LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                }

                GroupWalletAddressesUIState.CannotWatch -> {
                    EmptyState(
                        modifier = Modifier.padding(padding).readableContent(),
                        message = stringResource(Res.string.this_profile_cannot_watch_the_chain),
                        icon = Icons.Default.Visibility,
                    )
                }

                is GroupWalletAddressesUIState.Loaded -> {
                    val receive = state.receive
                    if (receive == null) {
                        LoadingDataIndicator(
                            modifier = Modifier.fillMaxWidth().padding(padding),
                            text = stringResource(Res.string.finding_the_group_s_coins)
                        )
                    } else {
                        AddressList(
                            modifier = Modifier.padding(padding),
                            receive = receive,
                            notify = notify,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressList(
    modifier: Modifier,
    receive: List<GroupAddress>,
    notify: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
    ) {
        item {
            Text(
                text = stringResource(Res.string.addresses_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(receive, key = { it.derivation.index }) { address ->
            AddressRow(
                address = address,
                onCopy = {
                    clipboardManager.setText(buildAnnotatedString { append(address.address) })
                    notify(addressCopied)
                },
            )
        }
    }
}

/**
 * One address: its index, the address itself, and what it has been used for.
 *
 * The current address takes the secondary container rather than Phoenix's
 * leading bar, so the emphasis is a colour pairing the theme has already
 * measured in all six schemes.
 */
@Composable
private fun AddressRow(address: GroupAddress, onCopy: () -> Unit) {
    val colors = if (address.status == GroupAddress.Status.CURRENT) {
        val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
        ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            headlineColor = onContainer,
            supportingColor = onContainer,
            leadingIconColor = onContainer,
            trailingIconColor = onContainer,
        )
    } else {
        ListItemDefaults.colors()
    }

    ListItem(
        colors = colors,
        leadingContent = {
            Text(
                text = address.derivation.index.toString(),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        headlineContent = {
            Text(
                text = address.address,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        },
        supportingContent = { Text(address.status.label(address.balance)) },
        trailingContent = {
            IconButton(onClick = onCopy) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(Res.string.copy_address_s, address.derivation.index.toString())
                )
            }
        },
    )
}

@Composable
private fun GroupAddress.Status.label(balance: Satoshi): String = when (this) {
    GroupAddress.Status.CURRENT -> stringResource(Res.string.being_handed_out_now)
    GroupAddress.Status.NEXT -> stringResource(Res.string.not_used_yet)
    GroupAddress.Status.USED -> if (balance > Satoshi(0)) {
        stringResource(Res.string.paid_s_sats, balance.grouped())
    } else {
        stringResource(Res.string.paid_spent_since)
    }
}
