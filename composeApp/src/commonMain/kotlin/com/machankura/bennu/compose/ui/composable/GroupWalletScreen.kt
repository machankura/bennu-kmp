package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Row
import com.machankura.bennu.compose.text.ProposedSpend
import com.machankura.bennu.compose.ui.view.state.SpendRow
import com.machankura.bennu.compose.ui.view.state.SpendStatus
import com.machankura.bennu.compose.wallet.GroupTransactionWatcher
import bennu.composeapp.generated.resources.spends
import bennu.composeapp.generated.resources.waiting_for_signatures
import bennu.composeapp.generated.resources.abandoned
import bennu.composeapp.generated.resources.signed_sending_it_to_the_network
import bennu.composeapp.generated.resources.sent_waiting_for_a_confirmation
import bennu.composeapp.generated.resources.confirmed
import bennu.composeapp.generated.resources.replaced_by_a_later_spend
import bennu.composeapp.generated.resources.send_again
import bennu.composeapp.generated.resources.bump_the_fee
import bennu.composeapp.generated.resources.sent_again
import bennu.composeapp.generated.resources.couldn_t_bump_the_fee
import androidx.compose.material.icons.filled.Send
import bennu.composeapp.generated.resources.send
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupSendRoute
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.address_copied
import bennu.composeapp.generated.resources.anyone_with_these_can_see_every_address
import bennu.composeapp.generated.resources.balance
import bennu.composeapp.generated.resources.balance_not_known_yet
import bennu.composeapp.generated.resources.change
import bennu.composeapp.generated.resources.coins
import bennu.composeapp.generated.resources.copy_address
import bennu.composeapp.generated.resources.couldn_t_ask_the_group_to_open_a_wallet
import bennu.composeapp.generated.resources.each_member_s_share_is_stored_unencrypted
import bennu.composeapp.generated.resources.export_watch_only
import bennu.composeapp.generated.resources.finding_the_group_s_coins
import bennu.composeapp.generated.resources.group_wallet
import bennu.composeapp.generated.resources.nothing_has_been_sent_to_this_wallet_yet
import bennu.composeapp.generated.resources.open_a_group_wallet
import bennu.composeapp.generated.resources.opening_a_wallet_asks_the_group_to_agree
import bennu.composeapp.generated.resources.receive_address
import bennu.composeapp.generated.resources.received
import bennu.composeapp.generated.resources.s_confirmations
import bennu.composeapp.generated.resources.s_dot_s
import bennu.composeapp.generated.resources.s_sats
import bennu.composeapp.generated.resources.s_sats_on_the_way
import bennu.composeapp.generated.resources.this_group_has_not_opened_a_wallet
import bennu.composeapp.generated.resources.this_profile_cannot_watch_the_chain
import bennu.composeapp.generated.resources.waiting_for_a_confirmation
import bennu.composeapp.generated.resources.watch_only_descriptors_copied
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.text.grouped
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.Decorative
import com.machankura.bennu.compose.ui.composable.widgets.EmptyState
import com.machankura.bennu.compose.ui.composable.widgets.ErrorState
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.composable.widgets.QRCodeView
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.rememberNotifier
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.theme.spacing
import com.machankura.bennu.compose.ui.view.model.GroupWalletViewModel
import com.machankura.bennu.compose.ui.view.state.GroupWalletUIState
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import com.machankura.bennu.compose.wallet.bip21Uri
import com.machankura.bennu.compose.wallet.GroupUtxo
import com.machankura.bennu.compose.wallet.GroupWalletState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource

/**
 * The group's wallet: what it holds, where to pay it, and the coins themselves.
 *
 * Pushed from its row on the group's screen. A group that has not opened a
 * wallet gets an empty state that says so -- and, for a member holding a share,
 * the way to ask the group to open one, with what that asks of everybody.
 *
 * The address is the only thing on the screen a person acts on outside the app,
 * so it is shown three ways: as a QR code, as selectable text, and on a copy
 * button. Every one of them is derived on this device from the room's key; the
 * statement the group signed is what says the wallet exists, never where it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupWalletScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    initialGroupWalletUIState: GroupWalletUIState = GroupWalletUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    wallets: GroupOnchainWallets?,
    watcher: GroupTransactionWatcher? = null,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupWalletViewModel: GroupWalletViewModel = viewModel(
        factory = GroupWalletViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            chain = chain,
            initialGroupWalletUIState = initialGroupWalletUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
            wallets = wallets,
            watcher = watcher,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupWalletUIState == GroupWalletUIState.Loading) {
            groupWalletViewModel.initiate()
        }
    }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotOpen = stringResource(Res.string.couldn_t_ask_the_group_to_open_a_wallet)
    val sentAgain = stringResource(Res.string.sent_again)
    val couldNotBump = stringResource(Res.string.couldn_t_bump_the_fee)
    val toSession = { sessionId: String ->
        onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.group_wallet),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupWalletViewModel.groupWalletUIState) { uiState ->
            when (val state = uiState) {
                is GroupWalletUIState.Error -> {
                    // Nothing to retry: the room came from a navigation argument.
                    ErrorState(
                        modifier = Modifier.padding(padding),
                        message = state.message,
                        onRetry = null
                    )
                }

                GroupWalletUIState.Loading -> {
                    LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))
                }

                is GroupWalletUIState.NotOpened -> {
                    EmptyState(
                        modifier = Modifier.padding(padding).readableContent(),
                        message = stringResource(Res.string.this_group_has_not_opened_a_wallet),
                        icon = Icons.Default.AccountBalanceWallet,
                        // Hidden rather than disabled, the way every gated way in
                        // on the group's screens is: a device with no share cannot
                        // propose, and a room with no key has nothing to open.
                        action = if (state.canSign && state.account != null) {
                            {
                                OpenWalletAction(
                                    isPending = groupWalletViewModel.isActionPending.value,
                                    onOpen = {
                                        groupWalletViewModel.openWallet(
                                            onSuccess = { sessionId ->
                                                // Onto the session: the wallet does
                                                // not exist until a quorum signs.
                                                onNavigateToRoute(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            onFailure = { notify(couldNotOpen) }
                                        )
                                    }
                                )
                            }
                        } else null
                    )
                }

                is GroupWalletUIState.Loaded -> {
                    OpenWallet(
                        modifier = Modifier.padding(padding),
                        state = state,
                        notify = notify,
                        // Behind both gates: a share to propose with, and a node to
                        // see the coins with -- a spend built blind is not one.
                        onSend = if (state.canSign && state.canWatch) {
                            {
                                onNavigateToRoute(
                                    GroupSendRoute(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = chatRoomId,
                                        relayHint = null
                                    )
                                )
                            }
                        } else null,
                        onOpenSpend = toSession,
                        onSendAgain = if (watcher != null) {
                            { sessionId -> groupWalletViewModel.sendAgain(sessionId) { notify(sentAgain) } }
                        } else null,
                        onBump = if (state.canSign) {
                            { sessionId ->
                                groupWalletViewModel.bumpFee(sessionId, onSuccess = toSession, onFailure = { notify(couldNotBump) })
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

/**
 * What opening a wallet asks of the group, said before the button rather than
 * after it: that any quorum can then spend together and nobody alone, and what
 * an unencrypted share on a member's device means once there are coins behind it.
 */
@Composable
private fun OpenWalletAction(isPending: Boolean, onOpen: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        Text(
            text = stringResource(Res.string.opening_a_wallet_asks_the_group_to_agree),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(Res.string.each_member_s_share_is_stored_unencrypted),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpen, enabled = !isPending) {
            Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative)
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
            Text(stringResource(Res.string.open_a_group_wallet))
        }
    }
}

@Composable
private fun OpenWallet(
    modifier: Modifier,
    state: GroupWalletUIState.Loaded,
    notify: (String) -> Unit,
    onSend: (() -> Unit)?,
    onOpenSpend: (sessionId: String) -> Unit,
    onSendAgain: ((sessionId: String) -> Unit)?,
    onBump: ((sessionId: String) -> Unit)?,
) {
    val clipboardManager = LocalClipboardManager.current
    val addressCopied = stringResource(Res.string.address_copied)
    val descriptorsCopied = stringResource(Res.string.watch_only_descriptors_copied)
    val walletState = state.walletState

    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize()
            .padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
    ) {
        item { BalanceCard(walletState) }

        onSend?.let { send ->
            item {
                Button(onClick = send) {
                    Icon(Icons.Default.Send, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.send))
                }
            }
        }

        when {
            !state.canWatch -> item {
                Text(
                    text = stringResource(Res.string.this_profile_cannot_watch_the_chain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            walletState?.receiveAddress == null -> item {
                LoadingDataIndicator(
                    fillScreen = false,
                    text = stringResource(Res.string.finding_the_group_s_coins)
                )
            }

            else -> {
                val address = walletState.receiveAddress!!

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                        ) {
                            Text(
                                text = stringResource(Res.string.receive_address),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            // BIP21, so a scanning wallet knows what the text is.
                            QRCodeView(bip21Uri(address))
                            SelectionContainer {
                                Text(text = address, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(buildAnnotatedString { append(address) })
                                    notify(addressCopied)
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                                Text(stringResource(Res.string.copy_address))
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(Res.string.coins),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (walletState.utxos.isEmpty()) {
                    item {
                        EmptyState(message = stringResource(Res.string.nothing_has_been_sent_to_this_wallet_yet))
                    }
                } else {
                    items(walletState.utxos, key = { it.utxo.outPoint.toString() }) { utxo ->
                        CoinRow(utxo = utxo, confirmations = walletState.confirmations(utxo))
                    }
                }
            }
        }

        // Under the coins and for every member, node or not: a spend is the
        // group's decision, and a member who cannot see the chain can still see
        // what the group has asked of it and where each request has got to.
        if (state.spends.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.spends),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(state.spends, key = { it.transaction.frostSigningSessionId }) { row ->
                SpendRowCard(
                    row = row,
                    chain = state.wallet.chain,
                    onOpen = { onOpenSpend(row.transaction.frostSigningSessionId) },
                    onSendAgain = onSendAgain?.takeIf { row.canSendAgain }?.let { { it(row.transaction.frostSigningSessionId) } },
                    onBump = onBump?.takeIf { row.canBump }?.let { { it(row.transaction.frostSigningSessionId) } },
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                TextButton(
                    onClick = {
                        val account = state.wallet.account
                        clipboardManager.setText(
                            buildAnnotatedString {
                                append(account.receiveDescriptor)
                                append("\n")
                                append(account.changeDescriptor)
                            }
                        )
                        notify(descriptorsCopied)
                    }
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = Decorative)
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                    Text(stringResource(Res.string.export_watch_only))
                }
                Text(
                    text = stringResource(Res.string.anyone_with_these_can_see_every_address),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One spend: what it sends, where it has got to, and what can still be done about it. */
@Composable
private fun SpendRowCard(
    row: SpendRow,
    chain: Chain,
    onOpen: () -> Unit,
    onSendAgain: (() -> Unit)?,
    onBump: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        ListItem(
            headlineContent = {
                Text(
                    text = (row.spend?.let { ProposedSpend.summarize(it, chain) } ?: ProposedSpend.UNREADABLE).detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = { Text(stringResource(row.status.label())) },
        )
        if (onSendAgain != null || onBump != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.compactPadding),
                horizontalArrangement = Arrangement.End,
            ) {
                onSendAgain?.let { TextButton(onClick = it) { Text(stringResource(Res.string.send_again)) } }
                onBump?.let { TextButton(onClick = it) { Text(stringResource(Res.string.bump_the_fee)) } }
            }
        }
    }
}

private fun SpendStatus.label() = when (this) {
    SpendStatus.WAITING_FOR_SIGNATURES -> Res.string.waiting_for_signatures
    SpendStatus.ABANDONED -> Res.string.abandoned
    SpendStatus.SIGNED -> Res.string.signed_sending_it_to_the_network
    SpendStatus.SENT -> Res.string.sent_waiting_for_a_confirmation
    SpendStatus.CONFIRMED -> Res.string.confirmed
    SpendStatus.REPLACED -> Res.string.replaced_by_a_later_spend
}

/** Confirmed and on-the-way said apart: only the first can be spent by a send-all. */
@Composable
private fun BalanceCard(walletState: GroupWalletState?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
        ) {
            Text(
                text = stringResource(Res.string.balance),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = walletState?.let { stringResource(Res.string.s_sats, it.confirmedBalance.grouped()) }
                    ?: stringResource(Res.string.balance_not_known_yet),
                style = MaterialTheme.typography.headlineMedium,
            )
            walletState?.pendingBalance?.takeIf { it.sat > 0 }?.let { pending ->
                Text(
                    text = stringResource(Res.string.s_sats_on_the_way, pending.grouped()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CoinRow(utxo: GroupUtxo, confirmations: Int) {
    ListItem(
        leadingContent = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = Decorative) },
        headlineContent = { Text(stringResource(Res.string.s_sats, utxo.amount.grouped())) },
        supportingContent = {
            val branch = stringResource(
                if (utxo.derivation.change == GroupWalletDerivation.CHANGE) Res.string.change else Res.string.received
            )
            val depth = if (confirmations == 0) {
                stringResource(Res.string.waiting_for_a_confirmation)
            } else {
                stringResource(Res.string.s_confirmations, confirmations.toString())
            }
            Text(text = stringResource(Res.string.s_dot_s, branch, depth), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}
