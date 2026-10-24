package com.machankura.bennu.compose.ui.composable

import bennu.composeapp.generated.resources.only_coins_confirmed_for_can_be_spent
import com.machankura.bennu.compose.ui.composable.widgets.delayText
import bennu.composeapp.generated.resources.while_the_wallet_is_new_at_most_s_sats
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import bennu.composeapp.generated.resources.sats
import bennu.composeapp.generated.resources.s_sats_confirmed_and_free_to_spend
import com.machankura.bennu.compose.wallet.GroupWalletRollout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import bennu.composeapp.generated.resources.Res
import bennu.composeapp.generated.resources.address
import bennu.composeapp.generated.resources.amount_in_sats
import bennu.composeapp.generated.resources.ask_the_group_to_send
import bennu.composeapp.generated.resources.couldn_t_ask_the_group_to_send
import bennu.composeapp.generated.resources.feerate_in_sats_per_byte
import bennu.composeapp.generated.resources.finding_the_group_s_coins
import bennu.composeapp.generated.resources.nothing_leaves_until_a_quorum_signs
import bennu.composeapp.generated.resources.s_sats_come_back_to_the_group_as_change
import bennu.composeapp.generated.resources.send_everything
import bennu.composeapp.generated.resources.send_from_the_group_s_wallet
import bennu.composeapp.generated.resources.sending_needs_a_share_and_a_node
import bennu.composeapp.generated.resources.sends_s_sats_with_a_fee_of_s_sats
import bennu.composeapp.generated.resources.that_is_too_little_to_send
import bennu.composeapp.generated.resources.that_isn_t_an_address_on_s
import bennu.composeapp.generated.resources.the_group_doesn_t_have_that_much_confirmed
import bennu.composeapp.generated.resources.the_group_has_nothing_confirmed_to_spend
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import bennu.composeapp.generated.resources.sats_are_whole_numbers
import bennu.composeapp.generated.resources.s_sats_left_over_go_to_the_fee
import bennu.composeapp.generated.resources.too_many_spends_are_open_with_change
import bennu.composeapp.generated.resources.that_is_more_sats_than_there_will_ever_be
import bennu.composeapp.generated.resources.that_isn_t_an_amount_in_sats
import com.machankura.bennu.compose.text.GroupedSats
import com.machankura.bennu.compose.text.SatsInput
import com.machankura.bennu.compose.text.grouped
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
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
import com.machankura.bennu.compose.ui.view.model.GroupSendViewModel
import com.machankura.bennu.compose.ui.view.state.GroupSendUIState
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import com.machankura.bennu.compose.wallet.GroupSpendBuilder
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.spend_from_s_s_guarded_wallet
import bennu.composeapp.generated.resources.a_subgroup
import com.machankura.bennu.compose.wallet.GroupSpend

/**
 * A spend from the group's wallet, proposed.
 *
 * The form says what it would build as it is filled in -- what leaves, the fee,
 * what comes back as change -- from the same build the proposal is made of, so
 * nothing a member reads here is an estimate of something else. Proposing it
 * moves onto the signing session: nothing has left the wallet, and nothing will
 * until a quorum has seen it and signed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSendScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    chain: Chain,
    initialGroupSendUIState: GroupSendUIState = GroupSendUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    wallets: GroupOnchainWallets?,
    guardedChatRoomId: String? = null,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val groupSendViewModel: GroupSendViewModel = viewModel(
        factory = GroupSendViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chatRoomId = chatRoomId,
            chain = chain,
            initialGroupSendUIState = initialGroupSendUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
            wallets = wallets,
            guardedChatRoomId = guardedChatRoomId,
        )
    )

    LaunchedEffect(true) {
        if (initialGroupSendUIState == GroupSendUIState.Loading) groupSendViewModel.initiate()
    }

    val notify = rememberNotifier(rememberCoroutineScope())
    val couldNotSend = stringResource(Res.string.couldn_t_ask_the_group_to_send)

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    // Whose wallet is in the title: a guardian's spend takes a
                    // descendant's coins, and nothing else on the form says so.
                    val guardian = ((groupSendViewModel.groupSendUIState as? GroupSendUIState.Ready)?.spender as? GroupSpend.Spender.Guardian)
                    Text(
                        text = if (guardian == null) stringResource(Res.string.send_from_the_group_s_wallet)
                            else stringResource(Res.string.spend_from_s_s_guarded_wallet, guardian.name ?: stringResource(Res.string.a_subgroup)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        ScreenStateTransition(groupSendViewModel.groupSendUIState) { uiState ->
            when (val state = uiState) {
                is GroupSendUIState.Error -> ErrorState(
                    modifier = Modifier.padding(padding),
                    message = state.message,
                    onRetry = null
                )

                GroupSendUIState.Loading -> LoadingDataIndicator(modifier = Modifier.fillMaxWidth().padding(padding))

                GroupSendUIState.Unavailable -> EmptyState(
                    modifier = Modifier.padding(padding).readableContent(),
                    message = stringResource(Res.string.sending_needs_a_share_and_a_node),
                    icon = Icons.Default.AccountBalanceWallet,
                )

                is GroupSendUIState.Ready -> Column(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(MaterialTheme.spacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                ) {
                    val preview = groupSendViewModel.preview()
                    // A problem with the amount is said on the amount field, beside what caused it.
                    val amountProblem = preview?.takeIf { groupSendViewModel.isAboutTheAmount(it) }

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        state = groupSendViewModel.addressField,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        label = { Text(stringResource(Res.string.address)) },
                    )

                    // The row is the target, so the label toggles too: one 48dp control with one name.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(
                                value = groupSendViewModel.sendEverything.value,
                                role = Role.Switch,
                                onValueChange = { groupSendViewModel.sendEverything.value = it },
                            )
                            .minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(Res.string.send_everything))
                        Switch(checked = groupSendViewModel.sendEverything.value, onCheckedChange = null)
                    }

                    // Hidden rather than cleared, so turning send-everything off again finds the amount typed.
                    if (!groupSendViewModel.sendEverything.value) {
                        val spendable = groupSendViewModel.spendable()
                        val cap = GroupWalletRollout.capFor(chain)
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = groupSendViewModel.amountField,
                            outputTransformation = GroupedSats,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            label = { Text(stringResource(Res.string.amount_in_sats)) },
                            suffix = { Text(stringResource(Res.string.sats)) },
                            isError = amountProblem != null,
                            supportingText = if (amountProblem == null && spendable == null && cap == null) null else {
                                {
                                    if (amountProblem != null) {
                                        Text(problemWith(amountProblem, chain))
                                    } else Column {
                                        spendable?.let { Text(stringResource(Res.string.s_sats_confirmed_and_free_to_spend, it.grouped())) }
                                        // Why a guardian's balance can be lower than the
                                        // wallet's: only coins past its own leaf's lock.
                                        groupSendViewModel.guardianDelay()?.let {
                                            Text(stringResource(Res.string.only_coins_confirmed_for_can_be_spent, delayText(it, chain)))
                                        }
                                        cap?.let { Text(stringResource(Res.string.while_the_wallet_is_new_at_most_s_sats, it.grouped())) }
                                    }
                                }
                            },
                        )
                    }

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        state = groupSendViewModel.feerateField,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(Res.string.feerate_in_sats_per_byte)) },
                    )

                    // Still finding change is still finding coins: neither is anything to correct.
                    if (state.walletState == null || preview == GroupSpendBuilder.Result.FindingChange) {
                        LoadingDataIndicator(fillScreen = false, text = stringResource(Res.string.finding_the_group_s_coins))
                    } else if (amountProblem == null) {
                        preview?.let { SendPreview(it, chain) }
                    }

                    Text(
                        text = stringResource(Res.string.nothing_leaves_until_a_quorum_signs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        enabled = preview is GroupSpendBuilder.Result.Built && !groupSendViewModel.isActionPending.value,
                        onClick = {
                            groupSendViewModel.propose(
                                onSuccess = { sessionId ->
                                    // Onto the session: the spend exists once it is
                                    // signed, and this form has done its part.
                                    onNavigateToRouteAndPopUpInclusive(
                                        FrostSigningRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            chatRoomId = chatRoomId,
                                            sessionId = sessionId,
                                        )
                                    )
                                },
                                onFailure = { notify(couldNotSend) },
                            )
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = Decorative)
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.relatedGap))
                        Text(stringResource(Res.string.ask_the_group_to_send))
                    }
                }
            }
        }
    }
}

/** What the form would build, or why it cannot. */
@Composable
private fun SendPreview(result: GroupSpendBuilder.Result, chain: Chain) {
    when (result) {
        is GroupSpendBuilder.Result.Built -> Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
            Text(
                text = stringResource(Res.string.sends_s_sats_with_a_fee_of_s_sats, result.amount.grouped(), result.fee.grouped()),
                style = MaterialTheme.typography.bodyLarge,
            )
            result.change?.let { change ->
                Text(
                    text = stringResource(Res.string.s_sats_come_back_to_the_group_as_change, change.grouped()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.absorbed.sat > 0) {
                Text(
                    text = stringResource(Res.string.s_sats_left_over_go_to_the_fee, result.absorbed.grouped()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> Text(
            text = problemWith(result, chain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Why [result] cannot be proposed, in words. */
@Composable
private fun problemWith(result: GroupSpendBuilder.Result, chain: Chain): String = when (result) {
    GroupSpendBuilder.Result.NotAnAddress ->
        stringResource(Res.string.that_isn_t_an_address_on_s, GroupWalletStatementEvent.chainName(chain))
    GroupSpendBuilder.Result.NothingToSpend -> stringResource(Res.string.the_group_has_nothing_confirmed_to_spend)
    GroupSpendBuilder.Result.NotEnough -> stringResource(Res.string.the_group_doesn_t_have_that_much_confirmed)
    GroupSpendBuilder.Result.TooManyOpen -> stringResource(Res.string.too_many_spends_are_open_with_change)
    is GroupSpendBuilder.Result.OverCap -> stringResource(Res.string.while_the_wallet_is_new_at_most_s_sats, result.cap.grouped())
    is GroupSpendBuilder.Result.InvalidAmount -> when (result.input) {
        SatsInput.NotWhole -> stringResource(Res.string.sats_are_whole_numbers)
        SatsInput.TooLarge -> stringResource(Res.string.that_is_more_sats_than_there_will_ever_be)
        else -> stringResource(Res.string.that_isn_t_an_amount_in_sats)
    }
    else -> stringResource(Res.string.that_is_too_little_to_send)
}
