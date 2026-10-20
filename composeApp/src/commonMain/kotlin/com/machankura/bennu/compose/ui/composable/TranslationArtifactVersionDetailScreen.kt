package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.database.model.MantraTranslationArtifactVersion
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.navigation.routes.TranslationChapterRoute
import com.machankura.bennu.compose.ui.composable.widgets.DetailRow
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.model.TranslationArtifactVersionDetailViewModel
import com.machankura.bennu.compose.ui.view.state.TranslationArtifactVersionDetailUIState
import com.machankura.bennu.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.chapters
import bennu.composeapp.generated.resources.details
import bennu.composeapp.generated.resources.no_chapters
import bennu.composeapp.generated.resources.propose
import bennu.composeapp.generated.resources.this_group_has_no_shared_key_so_it_cannot
import bennu.composeapp.generated.resources.translation_detail
import bennu.composeapp.generated.resources.chapter
import bennu.composeapp.generated.resources.chunks_translated
import androidx.compose.material3.SnackbarHost
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationArtifactVersionDetailScreen(
    activeUserPublicKey: HexKey,
    translationArtifactVersionId: String,
    chatRoomId: String,
    relayHint: String?,
    initialTranslationArtifactVersionDetailUIState: TranslationArtifactVersionDetailUIState = TranslationArtifactVersionDetailUIState.Loading,
    mantraRepository: MantraRepository,
    chatRepository: ChatRepository,
    frostSigningRepository: FrostSigningRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val translationDetailViewModel: TranslationArtifactVersionDetailViewModel = viewModel(
        factory = TranslationArtifactVersionDetailViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            translationArtifactVersionId = translationArtifactVersionId,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialTranslationArtifactVersionDetailUIState = initialTranslationArtifactVersionDetailUIState,
            mantraRepository = mantraRepository,
            chatRepository = chatRepository,
            frostSigningRepository = frostSigningRepository,
        )
    )

    ScreenStateTransition(translationDetailViewModel.translationDetailUIState) { uiState ->
    when (val translationDetailUIState = uiState) {
            is TranslationArtifactVersionDetailUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = translationDetailUIState.message)
                }
            }

            is TranslationArtifactVersionDetailUIState.Loaded -> {
                val translation = translationDetailUIState.translation
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = { Text(translation.name) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.space250),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        // Chapters the source version has and this translation
                        // does not. Above the list rather than after it: a chapter
                        // that is not here is invisible from a list of the ones
                        // that are.
                        val localChatRoom = translationDetailUIState.localChatRoom
                        val missingChapters = translationDetailUIState.missingChapters

                        if (missingChapters.isNotEmpty()) {
                            item {
                                Card {
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.PlaylistAdd,
                                                contentDescription = "Chapters missing from this translation"
                                            )
                                        },
                                        headlineContent = {
                                            Text(
                                                "${missingChapters.size} " +
                                                    (if (missingChapters.size == 1) "chapter is" else "chapters are") +
                                                    " not in this translation yet"
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                if (!translationDetailUIState.canSign) {
                                                    stringResource(Res.string.this_group_has_no_shared_key_so_it_cannot)
                                                } else {
                                                    "They were signed into the artifact after this " +
                                                        "translation. Ask the group to add them so they " +
                                                        "can be translated."
                                                }
                                            )
                                        },
                                        trailingContent = {
                                            TextButton(
                                                enabled = localChatRoom != null &&
                                                    translationDetailUIState.canSign &&
                                                    !translationDetailViewModel.isActionPending.value,
                                                onClick = {
                                                    if (localChatRoom == null) return@TextButton

                                                    translationDetailViewModel.catchUpMissingChapters(
                                                        localChatRoom = localChatRoom,
                                                        missingChapters = missingChapters,
                                                        onSuccess = { sessionId ->
                                                            onNavigateToRoute.invoke(
                                                                FrostSigningRoute(
                                                                    activeUserPublicKey = activeUserPublicKey,
                                                                    chatRoomId = chatRoomId,
                                                                    sessionId = sessionId
                                                                )
                                                            )
                                                        },
                                                        onFailure = {}
                                                    )
                                                }
                                            ) {
                                                Text(stringResource(Res.string.propose))
                                            }
                                        }
                                    )
                                }
                            }

                            item { HorizontalDivider() }
                        }

                        // Chapters with translation progress
                        item {
                            Text(
                                text = stringResource(Res.string.chapters),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (translationDetailUIState.chapters.isEmpty()) {
                            item { Text(stringResource(Res.string.no_chapters)) }
                        } else {
                            items(
                                items = translationDetailUIState.chapters,
                                key = { progress -> progress.chapter.id }
                            ) { progress ->
                                Card(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            TranslationChapterRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                translationChapterId = progress.chapter.id,
                                                chatRoomId = chatRoomId,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.Article,
                                                contentDescription = "Chapter"
                                            )
                                        },
                                        headlineContent = { Text(stringResource(Res.string.chapter, progress.chapter.index)) },
                                        supportingContent = {
                                            Text(stringResource(Res.string.chunks_translated, progress.translatedChunks, progress.totalChunks))
                                        }
                                    )
                                }
                            }
                        }

                        item { HorizontalDivider() }

                        // Details
                        item {
                            Text(
                                text = stringResource(Res.string.details),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        item {
                            Card {
                                DetailRow(label = "Name", value = translation.name)
                                DetailRow(label = "Dialect", value = translation.dialectId)
                                DetailRow(label = "Visibility", value = translation.visibility)
                                DetailRow(label = "License", value = translation.license)
                                DetailRow(label = "Source version", value = translation.artifactVersionId)
                                DetailRow(label = "Author", value = translation.publicKey)
                                DetailRow(label = "Created", value = translation.createdAt.toString())
                            }
                        }
                    }
                }
            }

            TranslationArtifactVersionDetailUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.translation_detail),
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
        if (initialTranslationArtifactVersionDetailUIState == TranslationArtifactVersionDetailUIState.Loading) {
            translationDetailViewModel.initiateTranslationArtifactVersionDetail()
        }
    }
}

@ConformancePreviews
@Composable
private fun TranslationArtifactVersionDetailScreenPreview() {
    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TranslationArtifactVersionDetailScreen(
                activeUserPublicKey = "",
                translationArtifactVersionId = "translationId",
                chatRoomId = "chatRoomId",
                relayHint = null,
                initialTranslationArtifactVersionDetailUIState = TranslationArtifactVersionDetailUIState.Loaded(
                    translation = MantraTranslationArtifactVersion(
                        id = "translationId",
                        publicKey = "author",
                        artifactVersionId = "versionId",
                        dialectId = "dialectId",
                        name = "Sesotho",
                        visibility = "private",
                        license = "cc",
                        signature = "",
                        chatRoomId = "chatRoomId"
                    )
                ),
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {}
            )
        }
    }
}
