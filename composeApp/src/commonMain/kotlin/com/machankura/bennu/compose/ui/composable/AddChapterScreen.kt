package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.MantraArtifact
import com.machankura.bennu.compose.database.model.MantraArtifactVersion
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.text.Markdown
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.model.AddChapterViewModel
import com.machankura.bennu.compose.ui.view.state.AddChapterUIState
import com.machankura.bennu.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.add_chapter
import bennu.composeapp.generated.resources.chapter_name
import bennu.composeapp.generated.resources.eg_chapter_1_the_beginning
import bennu.composeapp.generated.resources.original_text_markdown
import bennu.composeapp.generated.resources.paste_the_chapter_s_markdown_blank_lines
import bennu.composeapp.generated.resources.propose_chapter
import bennu.composeapp.generated.resources.this_artifact_has_no_version_for_a_chapter
import bennu.composeapp.generated.resources.add_chapter_to
import androidx.compose.material3.SnackbarHost
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.composable.widgets.buttons.ExtendedFab
import com.machankura.bennu.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChapterScreen(
    activeUserPublicKey: HexKey,
    artifactId: String,
    chatRoomId: String,
    relayHint: String?,
    initialAddChapterUIState: AddChapterUIState = AddChapterUIState.Loading,
    mantraRepository: MantraRepository,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRouteAndPopUpInclusive: (Route) -> Unit,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val addChapterViewModel: AddChapterViewModel = viewModel(
        factory = AddChapterViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            artifactId = artifactId,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialAddChapterUIState = initialAddChapterUIState,
            mantraRepository = mantraRepository,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
        )
    )

    ScreenStateTransition(addChapterViewModel.addChapterUIState) { uiState ->
    when (val addChapterUIState = uiState) {
            is AddChapterUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = addChapterUIState.message)
                }
            }

            is AddChapterUIState.Loaded -> {
                val nameFieldState = rememberTextFieldState()
                val originalTextFieldState = rememberTextFieldState()

                // Live counts / paragraph (chunk) preview from the markdown text.
                val originalText = originalTextFieldState.text.toString()
                val wordCount = Markdown.wordCount(originalText)
                val characterCount = Markdown.characterCount(originalText)
                val paragraphCount = Markdown.splitParagraphs(originalText).size

                // A chapter hangs off a version, and the group has to be able to
                // sign; without both there is nothing this screen can propose.
                val artifactVersion = addChapterUIState.artifactVersion

                // The chapter and a chunk per paragraph are signed in one session,
                // and a session signs a bounded number of events. Past that the
                // chapter has to be split in two, which is worth saying while there
                // is still a cursor in the text rather than after a failed propose.
                val tooManyChunks = paragraphCount > AddChapterViewModel.MAX_CHUNKS_PER_CHAPTER

                val canProposeChapter = artifactVersion != null &&
                    addChapterUIState.canSign &&
                    paragraphCount > 0 &&
                    !tooManyChunks

                // imePadding: this screen has a text field, and without it the software
                // keyboard covers whatever is being typed into.
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier.imePadding(),
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(Res.string.add_chapter_to, addChapterUIState.artifact.name)) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    },
                    bottomBar = {
                        BottomAppBar(
                            actions = {},
                            floatingActionButton = {
                                ExtendedFab(
                                    label = stringResource(Res.string.propose_chapter),
                                    icon = { Icon(Icons.Default.Add, contentDescription = "Propose chapter") },
                                    enabled = canProposeChapter,
                                    onClick = {
                                        if (artifactVersion == null) return@ExtendedFab

                                        addChapterViewModel.addChapter(
                                            localChatRoom = addChapterUIState.localChatRoom,
                                            artifactVersion = artifactVersion,
                                            nameField = nameFieldState,
                                            originalTextField = originalTextFieldState,
                                            onSuccess = { sessionId ->
                                                // Onto the session rather than back to
                                                // the artifact. Nothing has been created
                                                // yet -- the chapter appears when enough
                                                // members sign -- so landing on the list
                                                // it is not in would read as a failure.
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
                                                    ImplementationPendingRoute("Failed chapter")
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
                        if (!addChapterUIState.canSign) {
                            Text(
                                text = "This group has no shared key, so it cannot sign a " +
                                    "chapter into existence. Run a shared key ceremony first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (artifactVersion == null) {
                            Text(
                                text = stringResource(Res.string.this_artifact_has_no_version_for_a_chapter),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (tooManyChunks) {
                            Text(
                                text = "$paragraphCount chunks is more than the group can sign in " +
                                    "one go. Split the chapter so that no part of it is over " +
                                    "${AddChapterViewModel.MAX_CHUNKS_PER_CHAPTER} paragraphs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = nameFieldState,
                            leadingIcon = {
                                Icon(
                                    Icons.Default.DriveFileRenameOutline,
                                    contentDescription = "Name of the chapter"
                                )
                            },
                            label = { Text(stringResource(Res.string.chapter_name)) },
                            placeholder = { Text(stringResource(Res.string.eg_chapter_1_the_beginning)) },
                        )

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            state = originalTextFieldState,
                            label = { Text(stringResource(Res.string.original_text_markdown)) },
                            placeholder = { Text(stringResource(Res.string.paste_the_chapter_s_markdown_blank_lines)) },
                        )

                        Text(
                            // The chunk count is what the group is asked to sign
                            // alongside the chapter, so it is a count of the work
                            // being proposed rather than a curiosity about the text.
                            text = "$wordCount words · $characterCount characters · " +
                                "$paragraphCount ${if (paragraphCount == 1) "chunk" else "chunks"} " +
                                "of ${AddChapterViewModel.MAX_CHUNKS_PER_CHAPTER}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (tooManyChunks) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.typography.labelMedium.color
                            }
                        )
                    }
                }
            }

            AddChapterUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.add_chapter),
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
        if (initialAddChapterUIState == AddChapterUIState.Loading) {
            addChapterViewModel.initiateAddChapter()
        }
    }
}

@ConformancePreviews
@Composable
private fun AddChapterScreenPreview() {
    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AddChapterScreen(
                activeUserPublicKey = "",
                artifactId = "artifactId",
                chatRoomId = "chatRoomId",
                relayHint = null,
                initialAddChapterUIState = AddChapterUIState.Loaded(
                    localChatRoom = LocalChatRoom(
                        chatRoom = ChatRoom(
                            id = "chatRoomId",
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
