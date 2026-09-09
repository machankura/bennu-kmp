package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.repository.MantraRepository
import com.it.curated.compose.ui.composable.widgets.DetailRow
import com.it.curated.compose.ui.composable.navigation.routes.AddChapterRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddTranslationRoute
import com.it.curated.compose.ui.composable.navigation.routes.ChapterDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.composable.navigation.routes.TranslationArtifactVersionDetailRoute
import com.it.curated.compose.ui.composable.widgets.LoadingDataIndicator
import com.it.curated.compose.ui.theme.CuratedTheme
import com.it.curated.compose.ui.view.model.ArtifactDetailViewModel
import com.it.curated.compose.ui.view.state.ArtifactDetailUIState
import com.it.curated.compose.ui.theme.spacing
import curated.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curated.composeapp.generated.resources.add_chapter
import curated.composeapp.generated.resources.add_translation
import curated.composeapp.generated.resources.artifact_detail
import curated.composeapp.generated.resources.chapters
import curated.composeapp.generated.resources.details
import curated.composeapp.generated.resources.no_chapters_yet
import curated.composeapp.generated.resources.no_translations_yet
import curated.composeapp.generated.resources.no_versions
import curated.composeapp.generated.resources.translations
import curated.composeapp.generated.resources.version
import curated.composeapp.generated.resources.versions
import curated.composeapp.generated.resources.chapter
import curated.composeapp.generated.resources.words_characters
import androidx.compose.material3.SnackbarHost
import com.it.curated.compose.ui.composable.widgets.LocalSnackbarHostState
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.composable.widgets.ScreenStateTransition
import com.it.curated.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactDetailScreen(
    activeUserPublicKey: HexKey,
    artifactId: String,
    chatRoomId: String,
    relayHint: String?,
    initialArtifactDetailUIState: ArtifactDetailUIState = ArtifactDetailUIState.Loading,
    mantraRepository: MantraRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val artifactDetailViewModel: ArtifactDetailViewModel = viewModel(
        factory = ArtifactDetailViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            artifactId = artifactId,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialArtifactDetailUIState = initialArtifactDetailUIState,
            mantraRepository = mantraRepository,
        )
    )

    ScreenStateTransition(artifactDetailViewModel.artifactDetailUIState) { uiState ->
    when (val artifactDetailUIState = uiState) {
            is ArtifactDetailUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = artifactDetailUIState.message)
                }
            }

            is ArtifactDetailUIState.Loaded -> {
                val artifact = artifactDetailUIState.artifact
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = { Text(artifact.name) },
                            navigationIcon = {
                                IconButton(onClick = onNavigateBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back"
                                    )
                                }
                            },
                        )
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.space250),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        // Chapters
                        item {
                            Text(
                                text = stringResource(Res.string.chapters),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (artifactDetailUIState.chapters.isEmpty()) {
                            item { Text(stringResource(Res.string.no_chapters_yet)) }
                        } else {
                            items(
                                items = artifactDetailUIState.chapters,
                                key = { chapter -> chapter.id }
                            ) { chapter ->
                                Card(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            ChapterDetailRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                chapterId = chapter.id,
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
                                        overlineContent = { Text(stringResource(Res.string.chapter, chapter.index)) },
                                        headlineContent = { Text(chapter.name) },
                                        supportingContent = {
                                            Text(stringResource(Res.string.words_characters, chapter.wordCount, chapter.characterCount))
                                        }
                                    )
                                }
                            }
                        }

                        item {
                            TextButton(
                                enabled = artifactDetailUIState.versions.isNotEmpty(),
                                onClick = {
                                    onNavigateToRoute.invoke(
                                        AddChapterRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            artifactId = artifact.id,
                                            chatRoomId = chatRoomId,
                                            relayHint = relayHint
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.PostAdd,
                                    contentDescription = "Add chapter"
                                )
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(stringResource(Res.string.add_chapter))
                            }
                        }

                        item { HorizontalDivider() }

                        // Translations
                        item {
                            Text(
                                text = stringResource(Res.string.translations),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (artifactDetailUIState.translations.isEmpty()) {
                            item { Text(stringResource(Res.string.no_translations_yet)) }
                        } else {
                            items(
                                items = artifactDetailUIState.translations,
                                key = { translation -> translation.id }
                            ) { translation ->
                                Card(
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            TranslationArtifactVersionDetailRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                translationArtifactVersionId = translation.id,
                                                chatRoomId = chatRoomId,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                ) {
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.Translate,
                                                contentDescription = "Translation"
                                            )
                                        },
                                        headlineContent = { Text(translation.name) },
                                        supportingContent = { Text(translation.visibility) }
                                    )
                                }
                            }
                        }

                        item {
                            TextButton(
                                enabled = artifactDetailUIState.versions.isNotEmpty(),
                                onClick = {
                                    onNavigateToRoute.invoke(
                                        AddTranslationRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            artifactId = artifact.id,
                                            chatRoomId = chatRoomId,
                                            relayHint = relayHint
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Translate,
                                    contentDescription = "Add translation"
                                )
                                Spacer(modifier = Modifier.width(MaterialTheme.spacing.space125))
                                Text(stringResource(Res.string.add_translation))
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
                                DetailRow(label = "Name", value = artifact.name)
                                DetailRow(label = "Url", value = artifact.url)
                                DetailRow(label = "Visibility", value = artifact.visibility)
                                DetailRow(label = "License", value = artifact.license)
                                DetailRow(label = "Dialect", value = artifact.dialectId)
                                DetailRow(label = "Author", value = artifact.publicKey)
                                DetailRow(label = "Created", value = artifact.createdAt.toString())
                            }
                        }

                        // Versions
                        item {
                            Text(
                                text = stringResource(Res.string.versions),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (artifactDetailUIState.versions.isEmpty()) {
                            item { Text(stringResource(Res.string.no_versions)) }
                        } else {
                            items(
                                items = artifactDetailUIState.versions,
                                key = { version -> version.id }
                            ) { version ->
                                Card {
                                    ListItem(
                                        headlineContent = { Text(version.versionLabel) },
                                        overlineContent = { Text(stringResource(Res.string.version)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ArtifactDetailUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.artifact_detail),
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
        if (initialArtifactDetailUIState == ArtifactDetailUIState.Loading) {
            artifactDetailViewModel.initiateArtifactDetail()
        }
    }
}

@ConformancePreviews
@Composable
private fun ArtifactDetailScreenPreview() {
    CuratedTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ArtifactDetailScreen(
                activeUserPublicKey = "",
                artifactId = "artifactId",
                chatRoomId = "chatRoomId",
                relayHint = null,
                initialArtifactDetailUIState = ArtifactDetailUIState.Loaded(
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
                    )
                ),
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {}
            )
        }
    }
}
