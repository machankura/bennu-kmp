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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.ChatRoom
import to.curare.compose.database.model.FrostSigningSession
import to.curare.compose.database.model.intermdiate.LocalChatRoom
import to.curare.compose.database.model.types.FrostSigningStage
import to.curare.compose.managers.FrostSigningManager
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.text.ProposedEvent
import to.curare.compose.ui.composable.widgets.profile.ProfileAvatar
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.FrostSigningViewModel
import to.curare.compose.ui.view.state.FrostSigningUIState
import to.curare.compose.ui.theme.spacing
import to.curare.compose.ui.composable.widgets.Decorative
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.don_t_sign
import curare.composeapp.generated.resources.has_not_taken_part_yet
import curare.composeapp.generated.resources.members
import curare.composeapp.generated.resources.read_to_the_end_of_the_list_to_sign
import curare.composeapp.generated.resources.ready_to_sign
import curare.composeapp.generated.resources.sign
import curare.composeapp.generated.resources.sign_with_the_group_s_key
import curare.composeapp.generated.resources.signed_their_part
import curare.composeapp.generated.resources.events_signed_together
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.ConformancePreviews

/**
 * One signing session, and the member's decision about it.
 *
 * A ceremony gets three approval screens because it asks three different
 * questions. Signing asks one — sign this or do not — so there is one screen,
 * and it has to carry the whole case for the answer: what is being signed, who
 * else has agreed, and what the group is still waiting on.
 *
 * It stays useful after the decision. A session cannot finish until enough
 * members take part, so a member who has already signed still needs to see
 * whose door to knock on, and the ladder is the only place that says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostSigningScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    sessionId: String,
    initialFrostSigningUIState: FrostSigningUIState = FrostSigningUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateBack: () -> Unit,
) {
    val frostSigningViewModel: FrostSigningViewModel = viewModel(
        factory = FrostSigningViewModel.factory(
            chatRoomId = chatRoomId,
            sessionId = sessionId,
            activeUserPublicKey = activeUserPublicKey,
            initialFrostSigningUIState = initialFrostSigningUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    // Nothing loads the room or starts watching the session until this runs.
    LaunchedEffect(true) {
        if (initialFrostSigningUIState == FrostSigningUIState.Loading) {
            frostSigningViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.sign_with_the_group_s_key),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { padding ->
        when (val state = frostSigningViewModel.frostSigningUIState) {
            is FrostSigningUIState.Loading -> Loading(padding)

            is FrostSigningUIState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                Text(text = state.message, textAlign = TextAlign.Center)
            }

            is FrostSigningUIState.Loaded -> {
                // Loaded means the room is loaded, not the session: initiate() sets
                // this state and only then starts collecting, so the first emission
                // always has a null session. Reading that as "no such session" would
                // flash an error on the way in.
                val session = state.session ?: return@Scaffold Loading(padding)

                val proposed = frostSigningViewModel.proposedEvents(state.items)

                // Every event of the batch has to be readable before any of it can
                // be signed. A member cannot check what they cannot see, and a
                // batch is all-or-nothing: agreeing to the two that rendered would
                // be agreeing to the third as well.
                val readable = state.items.isNotEmpty() && proposed.size == state.items.size

                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .padding(padding)
                        .readableContent()
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(MaterialTheme.spacing.space250),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)
                ) {
                    WhatIsBeingSigned(proposed, state.items.size)

                    HorizontalDivider()

                    Text(
                        text = statusOf(session),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    session.failureReason?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = stringResource(Res.string.members),
                        style = MaterialTheme.typography.labelMedium
                    )

                    // Named rather than counted, for the same reason the ceremony's
                    // ladder names people: "1 of 2" does not tell anyone whose door
                    // to knock on, and a session stalls until somebody knocks.
                    state.localChatRoom.localParticipants
                        .distinctBy { it.participant.participantPublicKey }
                        .forEach { localParticipant ->
                            val member = localParticipant.participant.participantPublicKey

                            Card {
                                ListItem(
                                    leadingContent = {
                                        ProfileAvatar(
                                            publicKey = member,
                                            profile = localParticipant.profile
                                        )
                                    },
                                    trailingContent = {
                                        when {
                                            member in state.signed -> Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Signed",
                                                tint = MaterialTheme.colorScheme.primary
                                            )

                                            member in state.offeredNonce -> Icon(
                                                Icons.Default.HourglassEmpty,
                                                contentDescription = "Ready to sign"
                                            )

                                            else -> Icon(
                                                Icons.Default.RadioButtonUnchecked,
                                                contentDescription = "Not yet",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    headlineContent = {
                                        Text(
                                            text = localParticipant.profile
                                                ?.humanReadableNameOrPubkey()
                                                ?: member
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = when {
                                                member in state.signed -> stringResource(Res.string.signed_their_part)
                                                member in state.offeredNonce -> stringResource(Res.string.ready_to_sign)
                                                else -> stringResource(Res.string.has_not_taken_part_yet)
                                            }
                                        )
                                    }
                                )
                            }
                        }

                    // Asked rather than re-derived: the manager owns when a session
                    // is still waiting on its owner, and a second copy of that rule
                    // here is a second copy to keep in step.
                    if (FrostSigningManager.isAwaitingApproval(session, state.items)) {
                        HorizontalDivider()

                        Text(
                            text = "Nothing has been published from this device yet. Signing " +
                                "puts your share behind " +
                                (if (proposed.size > 1) "all ${proposed.size} of these events" else "this event") +
                                "; it cannot be taken back.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        // A batch can hide an event below the fold in a way one
                        // event cannot: what is off-screen is not further detail
                        // about the thing on screen, it is a different thing the
                        // member would also be signing. So a batch's Sign button
                        // waits until the list has been read to the end. maxValue
                        // is Int.MAX_VALUE until the first layout, and 0 when
                        // everything already fits.
                        val seenEverything = proposed.size <= 1 ||
                            (scrollState.maxValue != Int.MAX_VALUE && scrollState.value >= scrollState.maxValue)

                        if (readable && !seenEverything) {
                            Text(
                                text = stringResource(Res.string.read_to_the_end_of_the_list_to_sign),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                enabled = readable && seenEverything &&
                                    !frostSigningViewModel.isActionPending.value,
                                onClick = { frostSigningViewModel.approve(onNavigateBack) }
                            ) {
                                Icon(Icons.Default.Draw, contentDescription = Decorative)
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(stringResource(Res.string.sign))
                            }

                            // Declining stays available whatever the screen could
                            // not show. A member who cannot check what they are
                            // being asked to sign should still be able to say no,
                            // and saying nothing is indistinguishable from a phone
                            // in a pocket -- which leaves the group waiting.
                            TextButton(
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                enabled = !frostSigningViewModel.isActionPending.value,
                                onClick = { frostSigningViewModel.decline(onNavigateBack) }
                            ) {
                                Text(stringResource(Res.string.don_t_sign))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading(padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
        CircularProgressIndicator()
    }
}

/**
 * What the group is being asked to put its name to.
 *
 * One entry per event, each said as the thing it is rather than as an event --
 * see [ProposedEvent], which the room's list of proposals says it the same way
 * from. Nothing is described here that could not be read: a batch is
 * all-or-nothing, so a member who cannot see one of its events is being asked to
 * sign something they cannot check.
 */
@Composable
private fun WhatIsBeingSigned(events: List<Event>, expected: Int) {
    if (expected == 0 || events.size != expected) {
        Text(
            text = if (expected <= 1) {
                "This session's event could not be read, so there is nothing to check " +
                    "before signing. Don't sign it."
            } else {
                "Only ${events.size} of this session's $expected events could be read, so " +
                    "there is no way to check what you would be signing. Don't sign it."
            },
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space200)) {
        if (events.size > 1) {
            Text(
                text = stringResource(Res.string.events_signed_together, events.size),
                style = MaterialTheme.typography.labelMedium
            )
        }

        events.forEachIndexed { index, event ->
            if (index > 0) HorizontalDivider()

            OneThingBeingSigned(event)
        }

        Text(
            text = "Signed by the group, not by you. Once enough members sign, " +
                (if (events.size > 1) "these are" else "this is") +
                " published under the group's shared key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One event of the batch, described as the thing it is. */
@Composable
private fun OneThingBeingSigned(event: Event) {
    val summary = ProposedEvent.summarize(event)

    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space50)) {
        Text(text = summary.label, style = MaterialTheme.typography.labelMedium)

        Text(text = summary.detail, style = MaterialTheme.typography.titleMedium)
    }
}

private fun statusOf(session: FrostSigningSession): String = when (session.stage) {
    FrostSigningStage.COLLECTING_NONCES ->
        "Waiting for ${session.threshold} of ${session.participantCount} members to take part."

    FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES ->
        if (session.isSigner()) {
            "You are one of the signers. Waiting on the rest of them."
        } else {
            "Enough members took part without needing you. Waiting on them to sign."
        }

    FrostSigningStage.COMPLETE -> "Signed."

    FrostSigningStage.FAILED -> "Abandoned. Nothing was signed, and it is safe to ask again."
}

@ConformancePreviews
@Composable
private fun FrostSigningScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            FrostSigningScreen(
                activeUserPublicKey = "",
                chatRoomId = "chatRoomId",
                sessionId = "sessionId",
                initialFrostSigningUIState = FrostSigningUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "chatRoomId",
                            userPublicKey = "",
                            subject = "#Ekklesia",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        )
                    )
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateBack = {}
            )
        }
    }
}
