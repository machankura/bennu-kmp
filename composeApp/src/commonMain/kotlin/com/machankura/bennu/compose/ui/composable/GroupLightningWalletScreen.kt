package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.a_lightning_wallet_needs_four_signers
import bennu.composeapp.generated.resources.at_most_s_sats_in_flight
import bennu.composeapp.generated.resources.balance
import bennu.composeapp.generated.resources.channel_actions
import bennu.composeapp.generated.resources.channels
import bennu.composeapp.generated.resources.close_to_the_onchain_wallet
import bennu.composeapp.generated.resources.couldn_t_propose_this
import bennu.composeapp.generated.resources.couldn_t_send_s_sats
import bennu.composeapp.generated.resources.force_close
import bennu.composeapp.generated.resources.group_wallet_on_s
import bennu.composeapp.generated.resources.lightning_wallet
import bennu.composeapp.generated.resources.lsp_s
import bennu.composeapp.generated.resources.make_a_key
import bennu.composeapp.generated.resources.matches_what_your_device_signed
import bennu.composeapp.generated.resources.no_payments_yet
import bennu.composeapp.generated.resources.nobody_runs_this_wallet
import bennu.composeapp.generated.resources.node_s
import bennu.composeapp.generated.resources.open_a_lightning_wallet
import bennu.composeapp.generated.resources.open_an_onchain_wallet_on_s_first
import bennu.composeapp.generated.resources.opening_a_lightning_wallet_asks
import bennu.composeapp.generated.resources.payments
import bennu.composeapp.generated.resources.receive
import bennu.composeapp.generated.resources.received_s_sats
import bennu.composeapp.generated.resources.reported_by_the_operator_in_term_s
import bennu.composeapp.generated.resources.run_by_s_term_s
import bennu.composeapp.generated.resources.run_this_wallet_on_my_device
import bennu.composeapp.generated.resources.s_of_s_sats_are_the_group_s
import bennu.composeapp.generated.resources.s_sats
import bennu.composeapp.generated.resources.s_sats_can_be_received
import bennu.composeapp.generated.resources.see_the_key_ceremony
import bennu.composeapp.generated.resources.send
import bennu.composeapp.generated.resources.sent_s_sats_with_a_fee_of_s_sats
import bennu.composeapp.generated.resources.signers_needed_s_of_s
import bennu.composeapp.generated.resources.step_down
import bennu.composeapp.generated.resources.the_group_is_making_its_lightning_wallet_key
import bennu.composeapp.generated.resources.the_operator_hasn_t_reported_the_wallet_yet
import bennu.composeapp.generated.resources.the_operator_s_device_runs_the_node
import bennu.composeapp.generated.resources.this_group_has_no_lightning_wallet_key_yet
import bennu.composeapp.generated.resources.you
import bennu.composeapp.generated.resources.your_device_hasn_t_signed_for_this_channel
import bennu.composeapp.generated.resources.your_device_signed_for_s_sats
import bennu.composeapp.generated.resources.your_share_of_this_key_is_stored_unencrypted
import com.machankura.bennu.compose.database.model.GroupLightningPaymentRow
import com.machankura.bennu.compose.identity.Identity
import com.machankura.bennu.compose.managers.GroupLightningReports
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.GroupLightningRepository
import com.machankura.bennu.compose.text.grouped
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupLightningReceiveRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupLightningSendRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.IcebergCeremonyRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.Decorative
import com.machankura.bennu.compose.ui.composable.widgets.EmptyState
import com.machankura.bennu.compose.ui.composable.widgets.ErrorState
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.rememberNotifier
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.theme.spacing
import com.machankura.bennu.compose.ui.view.model.GroupLightningWalletViewModel
import com.machankura.bennu.compose.ui.view.state.GroupLightningWalletUIState
import com.machankura.bennu.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * The group's Lightning wallet: what it needs before it can exist, the statement that opened it, and
 * who runs its node. Balance, channels and payments are the operator's snapshot
 * (Phase 10), each channel checked against what this device last co-signed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupLightningWalletScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    lightningRepository: GroupLightningRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val viewModel: GroupLightningWalletViewModel = viewModel(
        factory = GroupLightningWalletViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            chain = GroupLightningRollout.NETWORK,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            lightningRepository = lightningRepository,
        )
    )
    LaunchedEffect(true) { viewModel.initiate() }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotPropose = stringResource(Res.string.couldn_t_propose_this)
    val network = GroupWalletStatementEvent.chainName(GroupLightningRollout.NETWORK)
    val toSession = { sessionId: String -> onNavigateToRoute(FrostSigningRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) }
    val toCeremony = { sessionId: String? -> onNavigateToRoute(IcebergCeremonyRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, sessionId = sessionId)) }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.lightning_wallet), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(viewModel.uiState) { uiState ->
            when (val state = uiState) {
                is GroupLightningWalletUIState.Error -> ErrorState(modifier = Modifier.padding(padding), message = state.message, onRetry = null)

                GroupLightningWalletUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                is GroupLightningWalletUIState.NeedsOnchainWallet -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.open_an_onchain_wallet_on_s_first, network),
                    icon = Icons.Default.AccountBalanceWallet,
                    action = {
                        Button(onClick = { onNavigateToRoute(GroupWalletRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId, relayHint = null, chain = network)) }) {
                            Text(stringResource(Res.string.group_wallet_on_s, network))
                        }
                    }
                )

                is GroupLightningWalletUIState.NeedsKey -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = when {
                        state.ceremony != null -> stringResource(Res.string.the_group_is_making_its_lightning_wallet_key)
                        !state.canMakeKey -> stringResource(Res.string.a_lightning_wallet_needs_four_signers)
                        else -> stringResource(Res.string.this_group_has_no_lightning_wallet_key_yet)
                    },
                    icon = Icons.Default.Key,
                    action = if (state.ceremony != null || state.canMakeKey) {
                        {
                            Button(onClick = { toCeremony(state.ceremony?.id) }) {
                                Text(stringResource(if (state.ceremony != null) Res.string.see_the_key_ceremony else Res.string.make_a_key))
                            }
                        }
                    } else null
                )

                is GroupLightningWalletUIState.NotOpened -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.opening_a_lightning_wallet_asks, network),
                    icon = Icons.Default.Bolt,
                    action = if (state.canPropose) {
                        {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                                Text(stringResource(Res.string.your_share_of_this_key_is_stored_unencrypted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { viewModel.openWallet(onSession = toSession, onFailure = { notify(couldNotPropose) }) }, enabled = !viewModel.isActionPending.value) {
                                    Icon(Icons.Default.Bolt, contentDescription = Decorative)
                                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                                    Text(stringResource(Res.string.open_a_lightning_wallet))
                                }
                            }
                        }
                    } else null
                )

                is GroupLightningWalletUIState.Opened -> OpenedWallet(
                    modifier = Modifier.padding(padding),
                    state = state,
                    onReceive = { onNavigateToRoute(GroupLightningReceiveRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId)) },
                    onSend = { onNavigateToRoute(GroupLightningSendRoute(activeUserPublicKey = activeUserPublicKey, chatRoomId = chatRoomId)) },
                    onClose = { channelId, force -> viewModel.close(channelId, force, onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                    isPending = viewModel.isActionPending.value,
                    onNominate = { viewModel.nominateSelf(onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                    onStepDown = { viewModel.stepDown(onSession = toSession, onFailure = { notify(couldNotPropose) }) },
                )
            }
        }
    }
}

@Composable
private fun OpenedWallet(modifier: Modifier, state: GroupLightningWalletUIState.Opened, onReceive: () -> Unit, onSend: () -> Unit, onClose: (channelId: String, force: Boolean) -> Unit, isPending: Boolean, onNominate: () -> Unit, onStepDown: () -> Unit) {
    val statement = state.wallet.statement
    val operatorName = state.operator?.publicKey?.let { key ->
        if (state.isOperator) stringResource(Res.string.you)
        else state.localChatRoom.localParticipants.firstOrNull { it.participant.participantPublicKey == key }?.profile?.humanReadableNameOrPubkey() ?: key.take(16)
    }
    LazyColumn(
        modifier = modifier.readableContent().fillMaxSize().padding(MaterialTheme.spacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
    ) {
        item { Balance(state.report, onReceive = onReceive.takeIf { state.operator?.publicKey != null }, onSend = onSend.takeIf { state.operator?.publicKey != null }) }
        state.report?.takeIf { it.checks.isNotEmpty() }?.let { report ->
            item { Text(stringResource(Res.string.channels), style = MaterialTheme.typography.titleSmall) }
            items(report.checks, key = { it.channel.channelId }) { ChannelRow(it, onClose = { force: Boolean -> onClose(it.channel.channelId, force) }.takeIf { !isPending }) }
        }
        item { Text(stringResource(Res.string.payments), style = MaterialTheme.typography.titleSmall) }
        if (state.payments.isEmpty()) {
            item { Text(stringResource(Res.string.no_payments_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.payments, key = { it.id }) { PaymentRow(it) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                    Text(
                        text = if (operatorName == null) stringResource(Res.string.nobody_runs_this_wallet) else stringResource(Res.string.run_by_s_term_s, operatorName, state.operator!!.term.toString()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(Res.string.the_operator_s_device_runs_the_node), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.isParticipant && !state.isOperator) {
                        Button(onClick = onNominate, enabled = !isPending) { Text(stringResource(Res.string.run_this_wallet_on_my_device)) }
                    }
                    if (state.isOperator) {
                        OutlinedButton(onClick = onStepDown, enabled = !isPending) { Text(stringResource(Res.string.step_down)) }
                    }
                }
            }
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Default.Key, contentDescription = Decorative) },
                headlineContent = { Text(stringResource(Res.string.signers_needed_s_of_s, statement.threshold.toString(), statement.participants.size.toString())) },
                supportingContent = { Text(stringResource(Res.string.at_most_s_sats_in_flight, Satoshi(statement.policy.inFlightCapSat).grouped())) },
            )
        }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Default.Router, contentDescription = Decorative) },
                headlineContent = { SelectionContainer { Text(stringResource(Res.string.node_s, statement.nodeId)) } },
                supportingContent = { SelectionContainer { Text(stringResource(Res.string.lsp_s, statement.lspNodeId)) } },
            )
        }
    }
}

/** The operator's figure for what the group can spend and receive, or a word that there is none yet. */
@Composable
private fun Balance(report: GroupLightningReports.Report?, onReceive: (() -> Unit)?, onSend: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.containerPadding), verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(stringResource(Res.string.balance), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (report == null) {
                Text(stringResource(Res.string.the_operator_hasn_t_reported_the_wallet_yet), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(stringResource(Res.string.s_sats, Satoshi(report.snapshot.spendableMsat / 1000).grouped()), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(Res.string.s_sats_can_be_received, Satoshi(report.snapshot.inboundMsat / 1000).grouped()), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(Res.string.reported_by_the_operator_in_term_s, report.snapshot.term.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Whoever runs the node makes the invoice and pays what the group signs for, so there is
            // nothing to ask while nobody does.
            if (onReceive != null || onSend != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)) {
                    onReceive?.let {
                        OutlinedButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.CallReceived, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                            Text(stringResource(Res.string.receive))
                        }
                    }
                    onSend?.let {
                        OutlinedButton(onClick = it) {
                            Icon(Icons.AutoMirrored.Filled.CallMade, contentDescription = Decorative)
                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                            Text(stringResource(Res.string.send))
                        }
                    }
                }
            }
        }
    }
}

/** One channel, and whether the operator's figure for it is the one this device co-signed. */
@Composable
private fun ChannelRow(check: GroupLightningReports.ChannelCheck, onClose: ((force: Boolean) -> Unit)?) {
    val channel = check.channel
    ListItem(
        leadingContent = {
            Icon(
                imageVector = if (check.agrees == false) Icons.Default.Warning else Icons.Default.Bolt,
                contentDescription = if (check.agrees == false) stringResource(Res.string.your_device_signed_for_s_sats, Satoshi(check.signedOursMsat!! / 1000).grouped()) else Decorative,
                tint = if (check.agrees == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(stringResource(Res.string.s_of_s_sats_are_the_group_s, Satoshi(channel.oursMsat / 1000).grouped(), Satoshi(channel.capacitySat).grouped())) },
        supportingContent = {
            Text(
                when (check.agrees) {
                    true -> stringResource(Res.string.matches_what_your_device_signed)
                    false -> stringResource(Res.string.your_device_signed_for_s_sats, Satoshi(check.signedOursMsat!! / 1000).grouped())
                    null -> stringResource(Res.string.your_device_hasn_t_signed_for_this_channel)
                },
                color = if (check.agrees == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // Closing is a proposal like any other: the menu only opens the signing session.
        trailingContent = onClose?.let { close ->
            {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.channel_actions))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.close_to_the_onchain_wallet)) }, onClick = { expanded = false; close(false) })
                        DropdownMenuItem(text = { Text(stringResource(Res.string.force_close)) }, onClick = { expanded = false; close(true) })
                    }
                }
            }
        },
    )
}

@Composable
private fun PaymentRow(payment: GroupLightningPaymentRow) {
    val amount = Satoshi(payment.amountMsat / 1000).grouped()
    ListItem(
        leadingContent = { Icon(if (payment.incoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade, contentDescription = Decorative) },
        headlineContent = {
            Text(
                when {
                    payment.incoming -> stringResource(Res.string.received_s_sats, amount)
                    payment.succeeded -> stringResource(Res.string.sent_s_sats_with_a_fee_of_s_sats, amount, Satoshi(payment.feesMsat / 1000).grouped())
                    else -> stringResource(Res.string.couldn_t_send_s_sats, amount)
                }
            )
        },
        supportingContent = payment.description?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    )
}
