package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.DkgSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.intermdiate.LocalParticipant
import to.curare.compose.database.model.types.DkgRitualStage
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.extensions.memberName
import to.curare.compose.managers.ChillDkgRitualManager
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.DkgRepository
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.profile.ProfileColor
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.database.model.types.DkgApprovalStep
import to.curare.compose.ui.composable.navigation.routes.DkgJoinApprovalRoute
import to.curare.compose.ui.composable.navigation.routes.DkgRound1ApprovalRoute
import to.curare.compose.ui.composable.navigation.routes.DkgRound2ApprovalRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.view.model.DkgRitualViewModel
import to.curare.compose.ui.view.state.DkgRitualUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.composable.widgets.Decorative
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.create_the_admin_room
import androidx.compose.material.icons.filled.AccountTree
import curare.composeapp.generated.resources.create_the_subgroup
import curare.composeapp.generated.resources.ask_the_parent_group_to_certify
import curare.composeapp.generated.resources.before_the_subgroup_exists_its_parent_signs
import curare.composeapp.generated.resources.the_parent_group_is_certifying_this_subgroup
import curare.composeapp.generated.resources.the_parent_group_could_not_certify_this_subgroup
import curare.composeapp.generated.resources.the_parent_group_has_certified_this_subgroup
import curare.composeapp.generated.resources.how_many_members_will_it_take_to_sign
import curare.composeapp.generated.resources.key
import curare.composeapp.generated.resources.members
import curare.composeapp.generated.resources.nothing_was_created_and_no_key_exists_it_is
import curare.composeapp.generated.resources.review_and_confirm
import curare.composeapp.generated.resources.review_and_contribute
import curare.composeapp.generated.resources.review_and_join
import curare.composeapp.generated.resources.shared_key
import curare.composeapp.generated.resources.start_key_ceremony
import curare.composeapp.generated.resources.the_ceremony_was_abandoned
import curare.composeapp.generated.resources.the_group_can_hold_one_key_together_split_so
import curare.composeapp.generated.resources.the_group_has_a_shared_key
import curare.composeapp.generated.resources.the_group_has_agreed_what_the_admin_room
import curare.composeapp.generated.resources.the_group_could_not_agree_what_the_admin
import curare.composeapp.generated.resources.the_group_is_agreeing_what_the_admin_room
import curare.composeapp.generated.resources.agree_the_groups_signing_key
import curare.composeapp.generated.resources.before_the_room_exists_the_group_signs
import curare.composeapp.generated.resources.this_is_fixed_once_the_ceremony_runs
import curare.composeapp.generated.resources.try_again
import curare.composeapp.generated.resources.your_share_of_it_is_on_this_device_only_your
import curare.composeapp.generated.resources.everyone_has_to_be_online_at_the_same_time
import curare.composeapp.generated.resources.of
import curare.composeapp.generated.resources.of_members_will_be_needed_to_sign_with_this
import curare.composeapp.generated.resources.shared_key_for
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.theme.ConformancePreviews

/**
 * The shared-key ceremony: a ChillDKG ritual run across the group's NIP-17
 * chat, shown as a ladder of rounds so members can see it is moving and who it
 * is waiting on.
 *
 * A DKG needs *everyone* present, which is unusual for a chat feature and the
 * main thing this screen has to communicate — hence a count against every step
 * rather than an indeterminate spinner.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DkgRitualScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    /** Which of the room's ceremonies to show; null takes the newest. */
    dkgSessionId: String? = null,
    initialDkgRitualUIState: DkgRitualUIState = DkgRitualUIState.Loading,
    activeIdentityStateFlow: StateFlow<Identity?>,
    chatRepository: ChatRepository,
    dkgRepository: DkgRepository,
    onNavigateToRoute: (Route) -> Unit = {},
) {
    val dkgRitualViewModel: DkgRitualViewModel = viewModel(
        factory = DkgRitualViewModel.factory(
            chatRoomId = chatRoomId,
            activeUserPublicKey = activeUserPublicKey,
            dkgSessionId = dkgSessionId,
            initialDkgRitualUIState = initialDkgRitualUIState,
            activeIdentityStateFlow = activeIdentityStateFlow,
            chatRepository = chatRepository,
            dkgRepository = dkgRepository
        )
    )

    ScreenStateTransition(dkgRitualViewModel.dkgRitualUIState) { uiState ->
    when (val dkgRitualUIState = uiState) {
            is DkgRitualUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = dkgRitualUIState.message, textAlign = TextAlign.Center)
                }
            }
            is DkgRitualUIState.Loaded -> {
                val session = dkgRitualUIState.session
                val participantCount = dkgRitualUIState.participantCount
                val canStartRitual = dkgRitualViewModel.canStartRitual()
                val isActionPending = dkgRitualViewModel.isActionPending.value

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(Res.string.shared_key_for, dkgRitualUIState.localChatRoom.chatRoom.subject ?: "this group"),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    },
                    bottomBar = {
                        val pending = dkgRitualUIState.pendingApproval

                        // The ceremony is stopped, waiting on this member, and will stay
                        // stopped until they act -- so this outranks everything else the
                        // bar could offer.
                        if (pending != null) {
                            BottomAppBar(
                                floatingActionButton = {
                                    ExtendedFloatingActionButton(
                                        onClick = {
                                            onNavigateToRoute(
                                                when (pending) {
                                                    DkgApprovalStep.HOST_KEY -> DkgJoinApprovalRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        dkgSessionId = dkgRitualUIState.session?.id
                                                    )
                                                    DkgApprovalStep.ROUND_1 -> DkgRound1ApprovalRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        dkgSessionId = dkgRitualUIState.session?.id
                                                    )
                                                    DkgApprovalStep.ROUND_2 -> DkgRound2ApprovalRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        dkgSessionId = dkgRitualUIState.session?.id
                                                    )
                                                }
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.Key, contentDescription = Decorative)
                                        Text(
                                            text = when (pending) {
                                                DkgApprovalStep.HOST_KEY -> stringResource(Res.string.review_and_join)
                                                DkgApprovalStep.ROUND_1 -> stringResource(Res.string.review_and_contribute)
                                                DkgApprovalStep.ROUND_2 -> stringResource(Res.string.review_and_confirm)
                                            }
                                        )
                                    }
                                },
                                actions = {}
                            )

                            return@Scaffold
                        }

                        // Any member can open a ritual — the coordinator has no say in
                        // the key — but only when there isn't one already running.
                        if (canStartRitual) {
                            BottomAppBar(
                                floatingActionButton = {
                                    ExtendedFloatingActionButton(
                                        onClick = { dkgRitualViewModel.startRitual() }
                                    ) {
                                        if (isActionPending) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        } else {
                                            Icon(Icons.Default.Key, contentDescription = "Start")
                                        }

                                        Text(
                                            text = if (session == null) stringResource(Res.string.start_key_ceremony) else stringResource(Res.string.try_again)
                                        )
                                    }
                                },
                                actions = {
                                    Text(
                                        modifier = Modifier.padding(start = MaterialTheme.spacing.space200),
                                        text = stringResource(Res.string.of, dkgRitualViewModel.threshold.value, participantCount),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .readableContent()
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(MaterialTheme.spacing.space125),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        if (session == null) {
                            Text(
                                text = stringResource(Res.string.the_group_can_hold_one_key_together_split_so),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                text = stringResource(Res.string.everyone_has_to_be_online_at_the_same_time, participantCount),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (canStartRitual) {
                                QuorumStepper(
                                    threshold = dkgRitualViewModel.threshold.value,
                                    participantCount = participantCount,
                                    quorumRange = dkgRitualViewModel.quorumRange(),
                                    onThresholdChange = dkgRitualViewModel::setThreshold
                                )
                            } else {
                                Text(
                                    text = "A shared key needs at least " +
                                        "${ChillDkgRitualManager.MINIMUM_PARTICIPANTS} members to split it between.",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        } else {
                            RitualProgress(
                                session = session,
                                participantCount = participantCount,
                                activeUserPublicKey = activeUserPublicKey,
                                members = dkgRitualUIState.ritualMembers,
                                hostKeyParticipants = dkgRitualUIState.hostKeyParticipants,
                                round1Participants = dkgRitualUIState.round1Participants,
                                round2Participants = dkgRitualUIState.round2Participants,
                                isActionPending = isActionPending,
                                adminGroupBlockedOn = dkgRitualUIState.adminGroupBlockedOn,
                                keyStateSession = dkgRitualUIState.keyStateSession,
                                isKeyStateSigned = dkgRitualUIState.isKeyStateSigned,
                                parentChatRoomId = dkgRitualUIState.parentChatRoomId,
                                isCertified = dkgRitualUIState.isCertified,
                                certificateSession = dkgRitualUIState.certificateSession,
                                onProposeBirthCertificate = dkgRitualViewModel::proposeBirthCertificate,
                                onProposeKeyState = dkgRitualViewModel::proposeKeyState,
                                onCreateAdminGroup = {
                                    dkgRitualViewModel.createAdminGroup(onNavigateToRoute)
                                }
                            )
                        }
                    }
                }
            }
            DkgRitualUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.shared_key),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    LoadingDataIndicator(fillScreen = false)
                    Spacer(modifier = Modifier.weight(2f))
                }
            }
        }
    }

    LaunchedEffect(true) {
        if (initialDkgRitualUIState == DkgRitualUIState.Loading) {
            dkgRitualViewModel.initiate()
        }
    }
}

/** The ladder, and the members it is climbing. */
@Composable
private fun RitualProgress(
    session: DkgSession,
    participantCount: Int,
    activeUserPublicKey: HexKey,
    members: List<LocalParticipant>,
    hostKeyParticipants: Set<HexKey>,
    round1Participants: Set<HexKey>,
    round2Participants: Set<HexKey>,
    isActionPending: Boolean,
    adminGroupBlockedOn: List<String>,
    keyStateSession: FrostSigningSession?,
    isKeyStateSigned: Boolean,
    parentChatRoomId: String?,
    isCertified: Boolean,
    certificateSession: FrostSigningSession?,
    onProposeBirthCertificate: () -> Unit,
    onProposeKeyState: () -> Unit,
    onCreateAdminGroup: () -> Unit,
) {
    val stage = session.stage
    val hostKeyCount = hostKeyParticipants.size
    val round1Count = round1Participants.size
    val round2Count = round2Participants.size

    // Above the failure branch so it holds in every state. Any member can open a
    // ceremony and it settles the group's signing quorum for good, so who opened
    // this one is worth saying whether it is running, finished or abandoned.
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ProfileColor.fromPublicKey(session.coordinatorPublicKey))) {
                append(session.coordinatorPublicKey.memberName(members, activeUserPublicKey))
            }
            append(" started this ceremony.")
        },
        style = MaterialTheme.typography.bodyMedium
    )

    if (stage == DkgRitualStage.FAILED) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space200),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = Decorative)
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)) {
                    Text(stringResource(Res.string.the_ceremony_was_abandoned), style = MaterialTheme.typography.titleSmall)
                    session.failureReason?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        stringResource(Res.string.nothing_was_created_and_no_key_exists_it_is),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        return
    }

    Text(
        text = stringResource(Res.string.of_members_will_be_needed_to_sign_with_this, session.threshold, participantCount),
        style = MaterialTheme.typography.bodyMedium
    )

    RitualStep(
        title = "Everyone joins",
        detail = "Each member publishes the key they will take part with.",
        count = hostKeyCount,
        total = participantCount,
        isDone = stage != DkgRitualStage.COLLECTING_HOST_KEYS
    )

    RitualStep(
        title = "Round one",
        detail = "Each member commits to their part of the key.",
        count = round1Count,
        total = participantCount,
        isDone = stage == DkgRitualStage.COLLECTING_ROUND_2 || stage == DkgRitualStage.COMPLETE
    )

    RitualStep(
        title = "Round two",
        detail = "Each member confirms everyone else's part.",
        count = round2Count,
        total = participantCount,
        isDone = stage == DkgRitualStage.COMPLETE
    )

    if (stage != DkgRitualStage.COMPLETE) {
        RitualRoster(
            members = members,
            activeUserPublicKey = activeUserPublicKey,
            hostKeyParticipants = hostKeyParticipants,
            round1Participants = round1Participants,
            round2Participants = round2Participants
        )
    }

    if (stage == DkgRitualStage.COMPLETE) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space200),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = Decorative)
                    Text(stringResource(Res.string.the_group_has_a_shared_key), style = MaterialTheme.typography.titleSmall)
                }

                session.thresholdPublicKey?.let { thresholdPublicKey ->
                    val clipboardManager = LocalClipboardManager.current

                    Text(text = stringResource(Res.string.key), style = MaterialTheme.typography.labelMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // In full, and wrapping rather than ellipsised. This is the
                        // group's key: it is what members compare out of band to
                        // check every device ended the ceremony on the same one, and
                        // a truncated key cannot be compared. Monospaced so the
                        // comparison is character by character.
                        Text(
                            text = thresholdPublicKey,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )

                        FilledIconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(thresholdPublicKey))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy the group's key"
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(Res.string.your_share_of_it_is_on_this_device_only_your),
                    style = MaterialTheme.typography.labelMedium
                )

                // What the group has to settle before there is a room: which
                // ceremony's key the admin room signs with. Signed first and
                // filed as the room is created, so the room's founding fact is
                // never something it has to go and ask about afterwards.
                //
                // Shown to every member, not only the coordinator, because every
                // member has to sign it -- the request itself is a line in this
                // chat, which is where they answer it.
                // A subgroup has one more thing to settle than a group does, and
                // it comes first: the parent's signature over this room's id. The
                // key state carries that certificate, so it cannot be proposed
                // until this rung is done -- which is why the order on screen is
                // the order the steps actually happen in rather than a layout
                // choice.
                if (parentChatRoomId != null) {
                    val certificateFailed = certificateSession?.stage == FrostSigningStage.FAILED

                    when {
                        isCertified -> Row(
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = Decorative)
                            Text(
                                text = stringResource(Res.string.the_parent_group_has_certified_this_subgroup),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        certificateFailed -> Text(
                            text = stringResource(Res.string.the_parent_group_could_not_certify_this_subgroup),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                        certificateSession != null -> Text(
                            text = stringResource(
                                Res.string.the_parent_group_is_certifying_this_subgroup,
                                certificateSession.threshold,
                                certificateSession.participantCount
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )

                        else -> Text(
                            text = stringResource(Res.string.before_the_subgroup_exists_its_parent_signs),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Offered to whoever holds a share of the parent's key, which
                    // the coordinator does by construction -- being a parent admin
                    // is how they got here. The other subgroup admins answer it in
                    // the parent's room, where the proposal lands, so there is
                    // nothing for them to press here.
                    if (session.isCoordinator() && !isCertified) {
                        Button(
                            onClick = { onProposeBirthCertificate() },
                            enabled = !isActionPending &&
                                (certificateSession == null || certificateFailed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isActionPending) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.AccountTree, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
                                Text(text = stringResource(Res.string.ask_the_parent_group_to_certify))
                            }
                        }
                    }
                }

                val keyStateFailed = keyStateSession?.stage == FrostSigningStage.FAILED

                when {
                    isKeyStateSigned -> Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = Decorative)
                        Text(
                            text = stringResource(Res.string.the_group_has_agreed_what_the_admin_room),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    keyStateFailed -> Text(
                        text = stringResource(Res.string.the_group_could_not_agree_what_the_admin),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    keyStateSession != null -> Text(
                        text = stringResource(
                            Res.string.the_group_is_agreeing_what_the_admin_room,
                            keyStateSession.threshold,
                            keyStateSession.participantCount
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )

                    else -> Text(
                        text = stringResource(Res.string.before_the_room_exists_the_group_signs),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Offered to whoever ran the ceremony. Any member's device could
                // derive the same room id and create it, but one of them has to go
                // first, and the coordinator is the member the group already watched
                // do the work.
                if (session.isCoordinator()) {
                    // Nothing is created until every member's key package is in
                    // hand, so this is the whole reason there is no room yet.
                    if (adminGroupBlockedOn.isNotEmpty()) {
                        Text(
                            text = "Waiting on ${adminGroupBlockedOn.joinToString(", ")} to " +
                                "open the app, so their device can publish the key it needs " +
                                "to be added.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // One button, two steps, in the order they have to happen:
                    // ask the group to agree the key, then make the room. The
                    // second is not offered early, because a room made before
                    // the group agreed would be the old order back again.
                    if (isKeyStateSigned) {
                        Button(
                            onClick = { onCreateAdminGroup() },
                            enabled = !isActionPending,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isActionPending) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Groups, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
                                Text(
                                    text = if (parentChatRoomId != null) {
                                        stringResource(Res.string.create_the_subgroup)
                                    } else {
                                        stringResource(Res.string.create_the_admin_room)
                                    }
                                )
                            }
                        }
                    } else {
                        // Offered again after a failure, and only then: a
                        // retry has to be a *new* session, never the same one
                        // resumed, because that session's nonce seeds have
                        // already been published against an aggregate.
                        // `propose` mints a fresh one every time, so tapping
                        // this is the safe retry by construction.
                        Button(
                            onClick = { onProposeKeyState() },
                            // A subgroup's key state carries the certificate and
                            // is refused without one, so this stays shut until the
                            // rung above it is done rather than opening a session
                            // that would throw.
                            enabled = !isActionPending &&
                                (keyStateSession == null || keyStateFailed) &&
                                (parentChatRoomId == null || isCertified),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isActionPending) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Key, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space100))
                                Text(text = stringResource(Res.string.agree_the_groups_signing_key))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every member, and how far each has got.
 *
 * The ladder above says "2 of 3", which is the one thing a stalled ceremony never
 * needs explaining — what it needs is a name. A DKG finishes only once every
 * member's device has taken part, so the group's whole recourse when it stops
 * moving is knowing whose door to knock on.
 */
@Composable
private fun RitualRoster(
    members: List<LocalParticipant>,
    activeUserPublicKey: HexKey,
    hostKeyParticipants: Set<HexKey>,
    round1Participants: Set<HexKey>,
    round2Participants: Set<HexKey>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space200),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
        ) {
            Text(text = stringResource(Res.string.members), style = MaterialTheme.typography.titleSmall)

            members
                .sortedBy { it.participant.participantPublicKey }
                .forEach { member ->
                    val publicKey = member.participant.participantPublicKey

                    // Named by the furthest round they have published, since that is
                    // the only thing this device knows about them for certain.
                    val progress = when (publicKey) {
                        in round2Participants -> "confirmed everyone's part"
                        in round1Participants -> "committed their part"
                        in hostKeyParticipants -> "joined"
                        else -> "not here yet"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Not decorative: the name beside it says who, and only the
                        // icon says whether they have contributed.
                        Icon(
                            imageVector = if (publicKey in hostKeyParticipants) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = if (publicKey in hostKeyParticipants) {
                                "Contributed"
                            } else {
                                "Not yet contributed"
                            }
                        )

                        Text(
                            modifier = Modifier.weight(1f),
                            text = publicKey.memberName(members, activeUserPublicKey),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            color = ProfileColor.fromPublicKey(publicKey),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = progress,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
        }
    }
}

@Composable
private fun RitualStep(
    title: String,
    detail: String,
    count: Int,
    total: Int,
    isDone: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space200),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Not decorative: the title says which round, the count says how far
                // along, and only the icon says whether it has finished.
                Icon(
                    imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isDone) "Complete" else "In progress"
                )
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(Res.string.of, count, total),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            LinearProgressIndicator(
                progress = { if (total == 0) 0f else count.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )

            Text(text = detail, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Same shape as the group-creation quorum picker, so the choice reads the same. */
@Composable
private fun QuorumStepper(
    threshold: Int,
    participantCount: Int,
    quorumRange: IntRange,
    onThresholdChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.space50),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)
    ) {
        Text(
            text = stringResource(Res.string.how_many_members_will_it_take_to_sign),
            style = MaterialTheme.typography.titleSmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = { onThresholdChange(threshold - 1) },
                enabled = threshold > quorumRange.first
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer signers")
            }

            Text(
                text = stringResource(Res.string.of, threshold, participantCount),
                style = MaterialTheme.typography.titleMedium
            )

            FilledIconButton(
                onClick = { onThresholdChange(threshold + 1) },
                enabled = threshold < quorumRange.last
            ) {
                Icon(Icons.Default.Add, contentDescription = "More signers")
            }
        }

        Text(
            text = stringResource(Res.string.this_is_fixed_once_the_ceremony_runs),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@ConformancePreviews
@Composable
private fun DkgRitualScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DkgRitualScreen(
                activeUserPublicKey = "",
                chatRoomId = "chatRoomId",
                initialDkgRitualUIState = DkgRitualUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Group discussions",
                            description = "See something... say something.",
                            initialGiftWrapPayloadId = null,
                            mlsGroupState = null
                        ),
                    )
                ),
                activeIdentityStateFlow = MutableStateFlow(null),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY
            )
        }
    }
}
