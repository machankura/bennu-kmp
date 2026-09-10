package to.curare.compose.ui.composable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.MantraChunk
import to.curare.compose.repository.MantraRepository
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.navigation.routes.TranslateChunkRoute
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.TranslationChapterViewModel
import to.curare.compose.ui.view.state.ChunkTranslationPair
import to.curare.compose.ui.view.state.TranslationChapterUIState
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.chapter_translation
import curare.composeapp.generated.resources.this_chapter_has_no_chunks
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationChapterScreen(
    activeUserPublicKey: HexKey,
    translationChapterId: String,
    chatRoomId: String,
    relayHint: String?,
    initialTranslationChapterUIState: TranslationChapterUIState = TranslationChapterUIState.Loading,
    mantraRepository: MantraRepository,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val translationChapterViewModel: TranslationChapterViewModel = viewModel(
        factory = TranslationChapterViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            translationChapterId = translationChapterId,
            chatRoomId = chatRoomId,
            relayHint = relayHint,
            initialTranslationChapterUIState = initialTranslationChapterUIState,
            mantraRepository = mantraRepository,
        )
    )

    ScreenStateTransition(translationChapterViewModel.translationChapterUIState) { uiState ->
    when (val translationChapterUIState = uiState) {
            is TranslationChapterUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.space600))
                    Text(text = translationChapterUIState.message)
                }
            }

            is TranslationChapterUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(Res.string.chapter_translation)) },
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
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize()
                    ) {
                        // Header row: dialect names.
                        item {
                            TableRow(
                                left = {
                                    Text(
                                        text = translationChapterUIState.originalDialectName,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                right = {
                                    Text(
                                        text = translationChapterUIState.translationDialectName,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                            )
                            HorizontalDivider()
                        }

                        if (translationChapterUIState.rows.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.containerPadding),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(stringResource(Res.string.this_chapter_has_no_chunks))
                                }
                            }
                        } else {
                            items(
                                items = translationChapterUIState.rows,
                                key = { pair -> pair.originalChunk.id }
                            ) { pair ->
                                ChunkTranslationRow(
                                    pair = pair,
                                    onClick = {
                                        onNavigateToRoute.invoke(
                                            TranslateChunkRoute(
                                                activeUserPublicKey = activeUserPublicKey,
                                                translationChapterId = translationChapterId,
                                                chunkId = pair.originalChunk.id,
                                                chatRoomId = chatRoomId,
                                                relayHint = relayHint
                                            )
                                        )
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            TranslationChapterUIState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space250),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(Res.string.chapter_translation),
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
        if (initialTranslationChapterUIState == TranslationChapterUIState.Loading) {
            translationChapterViewModel.initiateTranslationChapter()
        }
    }
}

@Composable
private fun ChunkTranslationRow(
    pair: ChunkTranslationPair,
    onClick: () -> Unit,
) {
    val translated = pair.translationChunk?.text?.takeIf { it.isNotBlank() }
    TableRow(
        left = { Text(pair.originalChunk.text) },
        // The whole translation cell opens the chunk translation editor. When
        // there is no translation yet, the original text is shown greyed out as
        // a placeholder. It stays plain text, laid out like the original cell
        // beside it, rather than a button with its own shape and padding.
        rightModifier = Modifier.minimumInteractiveComponentSize()
                            .clickable(onClick = onClick),
        right = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = translated ?: pair.originalChunk.text,
                    color = if (translated != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Translate this chunk",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
    )
}

@Composable
private fun TableRow(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    rightModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
    ) {
        Box(modifier = Modifier.weight(1f).padding(MaterialTheme.spacing.space150)) { left() }
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        Box(modifier = Modifier.weight(1f).then(rightModifier).padding(MaterialTheme.spacing.space150)) { right() }
    }
}

@ConformancePreviews
@Composable
private fun TranslationChapterScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TranslationChapterScreen(
                activeUserPublicKey = "",
                translationChapterId = "translationChapterId",
                chatRoomId = "chatRoomId",
                relayHint = null,
                initialTranslationChapterUIState = TranslationChapterUIState.Loaded(
                    originalDialectName = "English",
                    translationDialectName = "Sesotho",
                    rows = listOf(
                        ChunkTranslationPair(
                            originalChunk = sampleChunk("c1", "When he was nearly thirteen, my brother Jem got his arm badly broken."),
                            translationChunk = null
                        ),
                        ChunkTranslationPair(
                            originalChunk = sampleChunk("c2", "When enough years had gone by to enable us to look back on them..."),
                            translationChunk = null
                        ),
                    )
                ),
                mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                onNavigateToRoute = {},
                onNavigateBack = {}
            )
        }
    }
}

private fun sampleChunk(id: String, text: String) = MantraChunk(
    id = id,
    chapterId = "chapterId",
    publicKey = "author",
    text = text,
    index = 0,
    wordCount = 0,
    characterCount = 0,
    signature = "",
    chatRoomId = "chatRoomId"
)
