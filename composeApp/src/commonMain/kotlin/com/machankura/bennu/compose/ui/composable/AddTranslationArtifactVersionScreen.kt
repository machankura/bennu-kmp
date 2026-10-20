package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.MantraArtifact
import com.machankura.bennu.compose.database.model.MantraArtifactVersion
import com.machankura.bennu.compose.database.model.MantraDialect
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.managers.FrostSigningManager
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.model.AddTranslationArtifactVersionViewModel
import com.machankura.bennu.compose.ui.view.state.AddTranslationArtifactVersionUIState
import com.machankura.bennu.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.add_translation
import bennu.composeapp.generated.resources.propose_translation
import bennu.composeapp.generated.resources.this_artifact_has_no_version_for_a
import bennu.composeapp.generated.resources.translate_into_which_dialect
import bennu.composeapp.generated.resources.translate
import androidx.compose.material3.SnackbarHost
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.bennu.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTranslationArtifactVersionScreen(
    activeUserPublicKey: HexKey,
    artifactId: String,
    chatRoomId: String,
    relayHint: String?,
    initialAddTranslationArtifactVersionUIState: AddTranslationArtifactVersionUIState = AddTranslationArtifactVersionUIState.Loading,
    mantraRepository: MantraRepository,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val addTranslationArtifactVersionViewModel: AddTranslationArtifactVersionViewModel = viewModel(
        factory = AddTranslationArtifactVersionViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            artifactId = artifactId,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialAddTranslationArtifactVersionUIState = initialAddTranslationArtifactVersionUIState,
            mantraRepository = mantraRepository,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
        )
    )

    ScreenStateTransition(addTranslationArtifactVersionViewModel.addTranslationArtifactVersionUIState) { uiState ->
    when (val addTranslationUIState = uiState) {
            is AddTranslationArtifactVersionUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = addTranslationUIState.message)
                }
            }

            is AddTranslationArtifactVersionUIState.Loaded -> {
                // A translation is into a dialect the group already signed into
                // existence. Nothing is chosen to begin with: picking the first one
                // for somebody would be choosing the language of the work.
                var selectedDialectId: String? by remember { mutableStateOf(null) }
                val selectedDialect = addTranslationUIState.dialects
                    .firstOrNull { dialect -> dialect.id == selectedDialectId }

                // A translation hangs off a version, and the group has to be able to
                // sign; without both there is nothing this screen can propose.
                val artifactVersion = addTranslationUIState.artifactVersion
                val chapterCount = addTranslationUIState.chapters.size

                // The translation and a chapter apiece are signed together, and a
                // session signs a bounded number of events -- so a long artifact is
                // proposed in more than one, and the admins answer once per
                // session. That is a number worth seeing before the tap.
                val sessionCount = 1 + (
                    (chapterCount - AddTranslationArtifactVersionViewModel.MAX_CHAPTERS_PER_TRANSLATION)
                        .coerceAtLeast(0) + FrostSigningManager.MAX_BATCH_SIZE - 1
                    ) / FrostSigningManager.MAX_BATCH_SIZE

                val canProposeTranslation = artifactVersion != null &&
                    selectedDialect != null &&
                    addTranslationUIState.canSign

                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(Res.string.translate, addTranslationUIState.artifact.name)) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_translation),
                                    icon = { Icon(Icons.Default.Translate, contentDescription = "Propose translation") },
                                    enabled = canProposeTranslation,
                                    onClick = {
                                        if (artifactVersion == null || selectedDialect == null) return@ExtendedFab

                                        addTranslationArtifactVersionViewModel.addTranslation(
                                            localChatRoom = addTranslationUIState.localChatRoom,
                                            artifact = addTranslationUIState.artifact,
                                            artifactVersion = artifactVersion,
                                            dialect = selectedDialect,
                                            chapters = addTranslationUIState.chapters,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back to
                                                // the artifact. Nothing has been created
                                                // yet -- the translation appears when
                                                // enough members sign -- so landing on
                                                // the list it is not in would read as a
                                                // failure.
                                                onNavigateToRouteAndPopUpInclusive.invoke(
                                                    FrostSigningRoute(
                                                        activeUserPublicKey = activeUserPublicKey,
                                                        chatRoomId = chatRoomId,
                                                        sessionId = sessionId
                                                    )
                                                )
                                            },
                                            onFailure = {
                                                onNavigateToRoute.invoke(
                                                    ImplementationPendingRoute("Failed translation")
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.space250),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        if (!addTranslationUIState.canSign) {
                            Text(
                                text = "This group has no shared key, so it cannot sign a " +
                                    "translation into existence. Run a shared key ceremony first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (artifactVersion == null) {
                            Text(
                                text = stringResource(Res.string.this_artifact_has_no_version_for_a),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Text(stringResource(Res.string.translate_into_which_dialect))

                        if (addTranslationUIState.dialects.isEmpty()) {
                            // The only way out of this screen, and it is not on it:
                            // a dialect is the group's too, and asking for one is
                            // its own quorum from the room's own detail screen.
                            Text(
                                text = "This group has no dialects yet. Add one from the group's " +
                                    "details before translating anything into it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
                        ) {
                            addTranslationUIState.dialects.forEach { dialect ->
                                FilterChip(
                                    selected = selectedDialectId == dialect.id,
                                    onClick = { selectedDialectId = dialect.id },
                                    label = { Text(dialect.name) }
                                )
                            }
                        }

                        Text(
                            // What the group is being asked to sign, counted before
                            // the tap rather than described after it: the chapters
                            // are the rest of the batch. The empty case is worth
                            // its own sentence, so that a translation of an artifact
                            // nobody has written into yet does not read as broken.
                            text = if (chapterCount == 0) {
                                "The artifact has no chapters yet, so the group would sign the " +
                                    "translation on its own. Chapters signed afterwards are put " +
                                    "into it as they are added."
                            } else {
                                "The group signs the translation and " +
                                    "$chapterCount ${if (chapterCount == 1) "chapter" else "chapters"}" +
                                    (if (sessionCount == 1) "" else ", in $sessionCount sessions to sign") +
                                    ". Chunks are translated one at a time afterwards."
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            AddTranslationArtifactVersionUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.add_translation),
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
        if (initialAddTranslationArtifactVersionUIState == AddTranslationArtifactVersionUIState.Loading) {
            addTranslationArtifactVersionViewModel.initiateAddTranslation()
        }
    }
}

@ConformancePreviews
@Composable
private fun AddTranslationArtifactVersionScreenPreview() {
    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AddTranslationArtifactVersionScreen(
                activeUserPublicKey = "",
                artifactId = "artifactId",
                chatRoomId = "chatRoomId",
                relayHint = null,
                initialAddTranslationArtifactVersionUIState = AddTranslationArtifactVersionUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "",
                            userPublicKey = "",
                            subject = "Message title",
                            description = "See something. Say somethin",
                            initialGiftWrapPayloadId = "sdfaer",
                            mlsGroupState = null
                        ),
                    ),
                    artifact = MantraArtifact(
                        id = "artifactId",
                        publicKey = "author",
                        name = "To Kill a Mockingbird",
                        url = "https://harper.com/2-kill-Bird",
                        visibility = "private",
                        dialectId = "dialectId",
                        license = "cc",
                        chatRoomId = "chatRoomId",
                        signature = ""
                    ),
                    dialects = listOf(
                        MantraDialect(
                            id = "dialectId",
                            publicKey = "author",
                            name = "Sesotho",
                            country = "Lesotho",
                            language = "st",
                            chatRoomId = "chatRoomId",
                            signature = ""
                        )
                    ),
                    artifactVersion = MantraArtifactVersion(
                        id = "artifactVersionId",
                        artifactId = "artifactId",
                        publicKey = "author",
                        versionLabel = "1.0",
                        chatRoomId = "chatRoomId",
                        signature = ""
                    ),
                    canSign = true,
                ),
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRouteAndPopUpInclusive = {},
                onNavigateToRoute = {},
                onNavigateBack = {}
            )
        }
    }
}
