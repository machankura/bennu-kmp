package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.it.curated.compose.database.model.ChatRoom
import com.it.curated.compose.database.model.FrostSigningSession
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.database.model.types.FrostSigningStage
import com.it.curated.compose.extensions.toFormattedTimeAndDateString
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.FrostSigningRepository
import com.it.curated.compose.text.ProposedEvent
import com.it.curated.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.theme.CuratedTheme
import com.it.curated.compose.ui.view.model.ProposalListViewModel
import com.it.curated.compose.ui.view.state.ProposalListUIState
import com.it.curated.compose.ui.theme.spacing
import curated.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curated.composeapp.generated.resources.everything_else
import curated.composeapp.generated.resources.proposals
import curated.composeapp.generated.resources.review
import curated.composeapp.generated.resources.this_group_has_not_been_asked_to_sign
import curated.composeapp.generated.resources.waiting_for_you
import curated.composeapp.generated.resources.of_them_could_not_be_read
import androidx.compose.material3.SnackbarHost
import com.it.curated.compose.ui.composable.widgets.LocalSnackbarHostState
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.theme.ConformancePreviews

/**
 * Everything the group has asked its shared key to sign.
 *
 * The transcript shows a proposal as it happens, in among everything else that
 * was said, and answers one question about it: is this still asking something of
 * you. That is enough for a room signing one thing at a time and not enough for
 * a room signing two -- a chapter and its translation go out as two sessions,
 * and the transcript has no way to show that the second is still open once the
 * first is done.
 *
 * So: one row per proposal, each carrying its own state, oldest kept rather than
 * dropped. The ones still waiting on the reader come first, because those are
 * the only ones with anything to do in them; the rest are history, and history
 * is the other half of what this screen is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposalListScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    initialProposalListUIState: ProposalListUIState = ProposalListUIState.Loading,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
) {
    val proposalListViewModel: ProposalListViewModel = viewModel(
        factory = ProposalListViewModel.factory(
            chatRoomId = chatRoomId,
            initialProposalListUIState = initialProposalListUIState,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository
        )
    )

    LaunchedEffect(true) {
        if (initialProposalListUIState == ProposalListUIState.Loading) {
            proposalListViewModel.initiate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.proposals),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = proposalListViewModel.proposalListUIState) {
            is ProposalListUIState.Loading -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                CircularProgressIndicator()
            }

            is ProposalListUIState.Error -> Column(
                modifier = Modifier.fillMaxWidth().padding(padding).readableContent().padding(MaterialTheme.spacing.space250),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                Text(text = state.message, textAlign = TextAlign.Center)
            }

            is ProposalListUIState.Loaded -> {
                val open: (ProposalListUIState.Proposal) -> Unit = { proposal ->
                    onNavigateToRoute.invoke(
                        FrostSigningRoute(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            sessionId = proposal.session.id
                        )
                    )
                }

                LazyColumn(
                    modifier = Modifier.padding(padding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.space250),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                ) {
                    if (state.proposals.isEmpty()) {
                        item {
                            Text(
                                modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.space600),
                                text = stringResource(Res.string.this_group_has_not_been_asked_to_sign),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (state.waitingForYou.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(Res.string.waiting_for_you),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        items(
                            items = state.waitingForYou,
                            key = { it.session.id }
                        ) { proposal ->
                            ProposalCard(proposal = proposal, onClick = { open(proposal) })
                        }
                    }

                    if (state.rest.isNotEmpty()) {
                        // Headed only when there is a section above to tell it
                        // apart from; on its own it is the whole screen, which
                        // the title already says.
                        if (state.waitingForYou.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(Res.string.everything_else),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        items(
                            items = state.rest,
                            key = { it.session.id }
                        ) { proposal ->
                            ProposalCard(proposal = proposal, onClick = { open(proposal) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * One proposal, named after the first thing it signs.
 *
 * A batch is named after its first item because that is the one the rest hang
 * off -- a chapter's chunks carry the chapter's id, so the chapter is what the
 * proposal is about and the chunks are how big it is. The count says the rest.
 */
@Composable
private fun ProposalCard(
    proposal: ProposalListUIState.Proposal,
    onClick: () -> Unit,
) {
    val lead = proposal.signs.firstOrNull()

    // A row is named after its lead, and has to say something when there is no
    // lead to name it after. Two ways for that to happen and they are different
    // situations: a session can exist before its proposal has arrived, and a
    // proposal can arrive holding events this build cannot read.
    val label = lead?.label ?: "Proposal"
    val detail = lead?.detail?.takeIf { it.isNotBlank() }
        ?: if (proposal.eventCount == 0) {
            "Nothing has arrived to sign yet"
        } else {
            "None of its events could be read"
        }

    // Every colour inside this card is derived from the card, rather than reached for
    // independently, because ListItem does not inherit LocalContentColor -- its headline
    // comes from ListTokens.ItemLabelTextColor, which is `onSurface`. With the container
    // at `primaryContainer` and the headline at `onSurface`, the light scheme drew
    // #1B1B1B on #1B1B1B: 1.00:1, invisible, and applied to exactly the proposals that
    // await your signature. The icon tint (`primary`) was 1.22:1, the supporting text
    // 1.84:1 and the error line 2.67:1. Only the dark scheme was legible, because there
    // `primaryContainer` is black.
    val cardColors = if (proposal.awaitsYou) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    } else {
        CardDefaults.cardColors()
    }
    // `primaryContainer` is kept as the highlight so this stays a fix rather than a
    // restyle. It is a near-black card in the light scheme, and `secondaryContainer` --
    // the brand gold, 4.56:1 against its own content -- would read more like "this needs
    // you". That is a design call, not an accessibility one.
    val cardContentColor = cardColors.contentColor

    Card(
        onClick = onClick,
        colors = cardColors
    ) {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = cardContentColor,
                overlineColor = cardContentColor,
                supportingColor = cardContentColor,
                leadingIconColor = cardContentColor,
                trailingIconColor = cardContentColor,
            ),
            leadingContent = {
                Icon(
                    imageVector = when {
                        proposal.awaitsYou -> Icons.Default.Draw
                        proposal.session.stage == FrostSigningStage.COMPLETE ->
                            Icons.Default.CheckCircle
                        proposal.session.stage == FrostSigningStage.FAILED ->
                            Icons.Default.ErrorOutline
                        else -> Icons.Default.HourglassEmpty
                    },
                    // Carries the stage on its own, and carries it alone on the
                    // highlighted card where the failure state has no colour to spare.
                    contentDescription = when {
                        proposal.awaitsYou -> "Awaiting your signature"
                        proposal.session.stage == FrostSigningStage.COMPLETE -> "Signed"
                        proposal.session.stage == FrostSigningStage.FAILED -> "Failed"
                        else -> "Waiting on others"
                    },
                    tint = when {
                        // `error` is 2.67:1 on the highlighted card, so on that one the
                        // failure is carried by the icon shape and the supporting line
                        // rather than by colour -- which is the more robust signal
                        // anyway, and the only one available to a monochrome display.
                        proposal.session.stage == FrostSigningStage.FAILED &&
                            !proposal.awaitsYou -> MaterialTheme.colorScheme.error
                        else -> cardContentColor
                    }
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The same word the transcript uses for the same thing, so a
                    // member who came here looking for the line they tapped finds
                    // it saying what it said there.
                    if (proposal.awaitsYou) {
                        Text(
                            text = stringResource(Res.string.review),
                            style = MaterialTheme.typography.labelLarge,
                            color = cardContentColor
                        )
                    }

                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Open this proposal"
                    )
                }
            },
            overlineContent = {
                Text(text = label)
            },
            headlineContent = {
                Text(
                    text = detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space25)) {
                    if (proposal.eventCount > 1) {
                        Text(
                            text = "with ${proposal.eventCount - 1} more " +
                                (if (proposal.eventCount == 2) "event" else "events") +
                                ", signed together"
                        )
                    }

                    // A batch is all-or-nothing, so an item nobody can read is a
                    // reason to refuse the whole proposal. It is said on the row
                    // rather than left for the screen behind it.
                    if (proposal.unreadable > 0) {
                        Text(
                            text = stringResource(Res.string.of_them_could_not_be_read, proposal.unreadable),
                            color = if (proposal.awaitsYou) {
                                cardContentColor
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }

                    Text(text = statusOf(proposal))

                    Text(
                        text = "${proposal.proposerName} · " +
                            proposal.session.createdAt.toFormattedTimeAndDateString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}

/**
 * Where a proposal has got to, in a line.
 *
 * Shorter than the signing screen's version of the same question, and it has to
 * be: this is one row of a list somebody is scanning, not the page they are
 * deciding on. What it must never do is call a session finished when it is not,
 * or waiting when the decision has gone.
 */
private fun statusOf(proposal: ProposalListUIState.Proposal): String {
    val session = proposal.session

    if (proposal.awaitsYou) return "Needs your review"

    return when (session.stage) {
        FrostSigningStage.COLLECTING_NONCES ->
            "You agreed. Waiting for ${session.threshold} of " +
                "${session.participantCount} members to take part."

        FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES ->
            if (session.isSigner()) {
                "You are signing. Waiting on the rest of the signers."
            } else {
                "Enough members took part without you. Waiting on them to sign."
            }

        FrostSigningStage.COMPLETE -> "Signed by the group."

        // The reason is the whole content of a failure -- "declined on this
        // device" and "the group could not agree" are different things to
        // have happened -- so it stands in for the word "abandoned" whenever
        // there is one.
        FrostSigningStage.FAILED ->
            session.failureReason ?: "Abandoned. Nothing was signed."
    }
}

@ConformancePreviews
@Composable
private fun ProposalListScreenPreview() {
    val session = FrostSigningSession(
        id = "sessionId",
        chatRoomId = "chatRoomId",
        coordinatorPublicKey = "c".repeat(64),
        userPublicKey = "u".repeat(64),
        dkgSessionId = "dkgSessionId",
        threshold = 2,
        participantCount = 3,
        signerId = 1
    )

    CuratedTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ProposalListScreen(
                activeUserPublicKey = "u".repeat(64),
                chatRoomId = "chatRoomId",
                initialProposalListUIState = ProposalListUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "chatRoomId",
                            userPublicKey = "u".repeat(64),
                            subject = "#Ekklesia",
                            description = null,
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        )
                    ),
                    proposals = listOf(
                        ProposalListUIState.Proposal(
                            session = session,
                            proposerName = "Ada",
                            signs = listOf(
                                ProposedEvent.Summary("New chapter", "Genesis 1 · 797 words · 31 chunks")
                            ),
                            awaitsYou = true
                        ),
                        ProposalListUIState.Proposal(
                            session = session.copy(
                                id = "olderSessionId",
                                stage = FrostSigningStage.COMPLETE
                            ),
                            proposerName = "You",
                            signs = listOf(
                                ProposedEvent.Summary("New translation", "isiZulu · public · CC BY-SA")
                            )
                        )
                    )
                ),
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateBack = {},
                onNavigateToRoute = {}
            )
        }
    }
}
