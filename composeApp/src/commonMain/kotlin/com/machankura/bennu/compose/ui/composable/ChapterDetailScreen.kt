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
import androidx.compose.material.icons.filled.Segment
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.machankura.bennu.compose.database.model.MantraChapter
import com.machankura.bennu.compose.repository.MantraRepository
import com.machankura.bennu.compose.ui.composable.widgets.LoadingDataIndicator
import com.machankura.bennu.compose.ui.theme.BennuTheme
import com.machankura.bennu.compose.ui.view.model.ChapterDetailViewModel
import com.machankura.bennu.compose.ui.view.state.ChapterDetailUIState
import com.machankura.bennu.compose.ui.theme.spacing
import bennu.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import bennu.composeapp.generated.resources.chapter_detail
import bennu.composeapp.generated.resources.no_chunks
import bennu.composeapp.generated.resources.original_text
import bennu.composeapp.generated.resources.chapter_words_characters
import bennu.composeapp.generated.resources.chunk
import bennu.composeapp.generated.resources.chunks
import bennu.composeapp.generated.resources.words_characters
import androidx.compose.material3.SnackbarHost
import com.machankura.bennu.compose.ui.composable.widgets.LocalSnackbarHostState
import com.machankura.bennu.compose.ui.theme.readableContent
import com.machankura.bennu.compose.ui.composable.widgets.ScreenStateTransition
import com.machankura.bennu.compose.ui.composable.widgets.NavigateBackButton
import com.machankura.bennu.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDetailScreen(
    activeUserPublicKey: HexKey,
    chapterId: String,
    chatRoomId: String,
    relayHint: String?,
    initialChapterDetailUIState: ChapterDetailUIState = ChapterDetailUIState.Loading,
    mantraRepository: MantraRepository,
    onNavigateBack: () -> Unit,
) {
    val chapterDetailViewModel: ChapterDetailViewModel = viewModel(
        factory = ChapterDetailViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            chapterId = chapterId,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialChapterDetailUIState = initialChapterDetailUIState,
            mantraRepository = mantraRepository,
        )
    )

    ScreenStateTransition(chapterDetailViewModel.chapterDetailUIState) { uiState ->
    when (val chapterDetailUIState = uiState) {
            is ChapterDetailUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = chapterDetailUIState.message)
                }
            }

            is ChapterDetailUIState.Loaded -> {
                val chapter = chapterDetailUIState.chapter
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = { Text(chapter.name) },
                            navigationIcon = { NavigateBackButton(onNavigateBack) },
                        )
                    }
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize().padding(MaterialTheme.spacing.space250),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
                    ) {
                        item {
                            Text(
                                text = stringResource(Res.string.chapter_words_characters, chapter.index, chapter.wordCount, chapter.characterCount),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        // Original text (markdown, shown as-is).
                        item {
                            Text(
                                text = stringResource(Res.string.original_text),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        item {
                            Card {
                                Text(
                                    modifier = Modifier.padding(MaterialTheme.spacing.containerPadding),
                                    text = chapter.originalText,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        item { HorizontalDivider() }

                        // Chunks
                        item {
                            Text(
                                text = stringResource(Res.string.chunks, chapterDetailUIState.chunks.size),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (chapterDetailUIState.chunks.isEmpty()) {
                            item { Text(stringResource(Res.string.no_chunks)) }
                        } else {
                            items(
                                items = chapterDetailUIState.chunks,
                                key = { chunk -> chunk.id }
                            ) { chunk ->
                                Card {
                                    ListItem(
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.Segment,
                                                contentDescription = "Chunk"
                                            )
                                        },
                                        overlineContent = { Text(stringResource(Res.string.chunk, chunk.index)) },
                                        headlineContent = { Text(chunk.text) },
                                        supportingContent = {
                                            Text(stringResource(Res.string.words_characters, chunk.wordCount, chunk.characterCount))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ChapterDetailUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.chapter_detail),
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
        if (initialChapterDetailUIState == ChapterDetailUIState.Loading) {
            chapterDetailViewModel.initiateChapterDetail()
        }
    }
}

@ConformancePreviews
@Composable
private fun ChapterDetailScreenPreview() {
    BennuTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChapterDetailScreen(
                activeUserPublicKey = "",
                chapterId = "chapterId",
                chatRoomId = "chatRoomId",
                relayHint = null,
                initialChapterDetailUIState = ChapterDetailUIState.Loaded(
                    chapter = MantraChapter(
                        id = "chapterId",
                        artifactVersionId = "versionId",
                        publicKey = "author",
                        name = "Chapter 1 — The Beginning",
                        originalText = "When he was nearly thirteen...\n\nWhen enough years had gone by...",
                        index = 0,
                        wordCount = 12,
                        characterCount = 64,
                        signature = "",
                        chatRoomId = "chatRoomId"
                    )
                ),
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                onNavigateBack = {}
            )
        }
    }
}
