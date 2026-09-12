package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalProfileWithFollowing
import to.curare.compose.repository.ChatRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.LoadingDataIndicator
import to.curare.compose.ui.composable.widgets.dialogs.NewChatBottomSheetDialog
import to.curare.compose.ui.composable.widgets.dialogs.StartDirectMessageToNpubOrNip05Dialog
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.view.model.ChatRoomListViewModel
import to.curare.compose.ui.view.model.HomeScreenType
import to.curare.compose.ui.view.model.HomeViewModel
import to.curare.compose.ui.view.state.HomeScreenUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.launch
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.curare
import curare.composeapp.generated.resources.new_chat
import to.curare.compose.ui.composable.widgets.ErrorState
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import androidx.compose.ui.Alignment
import to.curare.compose.repository.DkgRepository
import to.curare.compose.repository.FrostSigningRepository
import to.curare.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.EmptyState
import to.curare.compose.ui.theme.breakpoint
import to.curare.compose.ui.theme.listPaneWidthFor
import to.curare.compose.ui.theme.readableContent
import curare.composeapp.generated.resources.pick_a_conversation_to_read_it_here
import to.curare.compose.ui.composable.widgets.ScreenStateTransition
import to.curare.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    activeUserPublicKey: HexKey,
    initialHomeScreenUIState: HomeScreenUIState = HomeScreenUIState.Loading,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateToDirectMessageDetail: (Route) -> Unit,
    onNavigateToChatRoomCreation: () -> Unit,
    nostrRepository: NostrRepository,
    chatRepository: ChatRepository,
    // Only the detail pane uses this, and only from the expanded breakpoint up. It is a
    // required parameter rather than a nullable one because a home screen that silently
    // loses its detail pane on a desktop is a worse failure than a compile error.
    frostSigningRepository: FrostSigningRepository,
    dkgRepository: DkgRepository,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    val openNpubDialog = remember { mutableStateOf(false) }

    // Null below the expanded breakpoint, which is where the room list is the whole screen
    // and tapping a room navigates, exactly as it did before panes existed.
    val listPaneWidth = listPaneWidthFor(MaterialTheme.breakpoint)

    // The room the detail pane is showing. Two saveable strings rather than the route
    // object, because that is all the route carries and both survive a process death that
    // a `@Serializable` route would need a Saver to survive.
    var selectedChatRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRelayHint by rememberSaveable { mutableStateOf<String?>(null) }

    // Tapping a room means two different things at two widths, and the list does not need
    // to know which: it hands over a `ChatRoomMessagingRoute` either way, and this decides
    // whether that is a destination to push or a selection to make.
    val onOpenChatRoom: (Route) -> Unit = { route ->
        if (listPaneWidth != null && route is ChatRoomMessagingRoute) {
            selectedChatRoomId = route.chatRoomId
            selectedRelayHint = route.relayHint
        } else {
            onNavigateToDirectMessageDetail(route)
        }
    }

    val homeScreenViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            activeUserPublicKey = activeUserPublicKey,
            initialHomeScreenUIState = initialHomeScreenUIState,
            nostrRepository = nostrRepository,
            pagerState = rememberPagerState { HomeScreenType.entries.size }
        ),
    )

    ScreenStateTransition(homeScreenViewModel.homeScreenUIState) { uiState ->
    when (val homeScreenUIState = uiState) {
            HomeScreenUIState.Error -> {
                Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
                    Column(
                        modifier = Modifier.padding(innerPadding).readableContent().fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        ErrorState()
                    }
                }

            }
            is HomeScreenUIState.Loaded -> {
                Scaffold(
                    snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
                    modifier = Modifier,
                    topBar = {
                        // No colour override. It was `containerColor = primaryContainer` with
                        // `titleContentColor = primary`, which in the light scheme is #000000
                        // on #1B1B1B -- 1.22:1, a black title on a near-black bar. The
                        // default is `surface`/`onSurface` and needs no help.
                        TopAppBar(
                            title = {
                                // "Curare". The launcher label, the desktop window title, the
                                // landing screen and the package name all say so, and since the
                                // rebrand so does `UserAgent.CLIENT_NAME` -- the one that goes on
                                // the wire, as the `client` tag on a published relay list. The two
                                // brands before it are gone from live code too -- what was
                                // `TorchTheme`, then `CuratedTheme`, is `CurareTheme`, and no
                                // error string names either.
                                Text(stringResource(Res.string.curare))
                            },
                            // No navigation icon and no actions. The avatar in the leading
                            // slot and the search icon in the trailing one were the app's only
                            // two peer surfaces, and they are now items in the navigation
                            // component -- a bar on a phone, a rail on a wide window. Leaving
                            // them here as well would be two routes to one destination, which
                            // is the thing M3's "swap only functionally equivalent components"
                            // caution is about.
                        )

                    },
                    floatingActionButton = {
                        // Only while there is one pane. With two, the scaffold's FAB slot is
                        // the bottom-right of the *window*, which is on top of the
                        // transcript's send button; M3 puts a list-detail layout's primary
                        // action in the list pane, and so does the branch below.
                        if (listPaneWidth == null) NewChatButton { showBottomSheet = true }
                    }
                ) { innerPadding ->
                    @Composable
                    fun ChatRoomListPane(modifier: Modifier) = Column(
                        modifier = modifier
                    ) {
                        PrimaryScrollableTabRow(
                            modifier = Modifier.padding(MaterialTheme.spacing.space125),
                            edgePadding = 10.dp,
                            selectedTabIndex = homeScreenViewModel.pagerState.currentPage,
                        ) {
                            HomeScreenType.entries.forEachIndexed { index, destination ->
                                Tab(
                                    selected = homeScreenViewModel.pagerState.currentPage == index,
                                    onClick = {
                                        scope.launch {
                                            homeScreenViewModel.pagerState.animateScrollToPage(destination.ordinal)
                                        }
                                    },
                                    text = {
                                        Text(
                                            text = destination.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            HorizontalPager(
                                state = homeScreenViewModel.pagerState,
                            ) { pageIndex ->
                                when (val homeScreenTypeType = HomeScreenType.entries[pageIndex]) {
                                    HomeScreenType.Messages -> {
                                        val chatRoomListViewModel: ChatRoomListViewModel = viewModel(
                                            key = homeScreenTypeType.name,
                                            factory = ChatRoomListViewModel.factory(
                                                initialChatRoomListUIState = to.curare.compose.ui.view.state.ChatRoomListUIState.Loading,
                                                publicKey = homeScreenUIState.profileWithFollowing.profile.publicKey,
                                                nostrRepository = nostrRepository,
                                                chatRepository = chatRepository
                                            ),
                                        )

                                        key(true) {
                                            chatRoomListViewModel.RenderFeed(
                                                onNavigateToDirectMessageDetail = onOpenChatRoom
                                            )
                                        }

                                        key(true) {
                                            chatRoomListViewModel.initiate()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (listPaneWidth == null) {
                        // Compact and medium: the list is the screen, and a room is a route.
                        // Byte for byte the layout this screen has always had.
                        ChatRoomListPane(
                            Modifier.padding(innerPadding).readableContent().fillMaxSize()
                        )
                    } else {
                        // Expanded and up: the list keeps a fixed width and the room fills the
                        // rest. No `readableContent()` on the pair -- capping the two panes
                        // together to one column's measure is the opposite of what a second
                        // pane is for. Each pane holds its own content to a measure instead,
                        // and `ChatRoomMessagingScreen` already does.
                        Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(listPaneWidth)
                                    .fillMaxHeight()
                                    // The pane's width is the one thing about this layout that
                                    // is a number rather than a rule, and the only way to check
                                    // a number is to measure it in a composition.
                                    .testTag(ChatListPaneTag)
                            ) {
                                ChatRoomListPane(Modifier.fillMaxSize())

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(MaterialTheme.spacing.containerPadding)
                                ) {
                                    NewChatButton { showBottomSheet = true }
                                }
                            }

                            Spacer(modifier = Modifier.width(MaterialTheme.spacing.paneGap))

                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                val chatRoomId = selectedChatRoomId
                                if (chatRoomId == null) {
                                    EmptyState(
                                        message = stringResource(Res.string.pick_a_conversation_to_read_it_here)
                                    )
                                } else {
                                    // Keyed on the room, so switching rooms rebuilds the
                                    // screen's view models rather than feeding a new id to
                                    // ones already subscribed to another room's relay.
                                    key(chatRoomId) {
                                    ChatRoomMessagingScreen(
                                        activeUserPublicKey = activeUserPublicKey,
                                        chatRoomId = chatRoomId,
                                        relayHint = selectedRelayHint,
                                        nostrRepository = nostrRepository,
                                        chatRepository = chatRepository,
                                        frostSigningRepository = frostSigningRepository,
                                        dkgRepository = dkgRepository,
                                        // "Replace what is on screen" is a route push on a
                                        // phone and a change of selection here. It fires when
                                        // a conversation that did not exist yet has just been
                                        // created and has an id at last.
                                        onNavigateToRouteAndPopUpInclusive = onOpenChatRoom,
                                        onNavigateToRoute = onNavigateToRoute,
                                        // The list is beside it; there is nothing to pop.
                                        onNavigateBack = null,
                                    )
                                    }
                                }
                            }
                        }
                    }

                    if (showBottomSheet) {
                        NewChatBottomSheetDialog(
                            scope = scope,
                            sheetState = sheetState,
                            onSetShowBottomSheetUpdate = { toggle ->
                                showBottomSheet = toggle
                            },
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateToChatRoomCreation = onNavigateToChatRoomCreation,
                            onStartDirectMessageChatWithNpub = {
                                openNpubDialog.value = true
                            }
                        )
                    }

                    when {
                        openNpubDialog.value -> {
                            StartDirectMessageToNpubOrNip05Dialog(
                                activeUserPublicKey = activeUserPublicKey,
                                scope = scope,
                                toggleOpenDialogSetting = {
                                    openNpubDialog.value = false
                                },
                                onNavigateToRoute = onNavigateToRoute
                            )
                        }
                    }
                }
            }
            HomeScreenUIState.Loading -> {
                LoadingDataIndicator()
            }
        }
    }

    key(true) {
        homeScreenViewModel.initiate()
    }
}

@ConformancePreviews
@Composable
private fun HomeScreenPreview() {
    CurareTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            HomeScreen(
                activeUserPublicKey = "",
                initialHomeScreenUIState = HomeScreenUIState.Loaded(
                    profileWithFollowing = LocalProfileWithFollowing(
                        nostrEvent = NostrEvent(
                            id = "eventId",
                            pubKey = "publicKey",
                            content = """Lorem Ipsum is simply dummy text of the printing and typesetting industry. 
                                
Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. 
                                
It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.""".trimIndent(),
                            tags = emptyArray(),
                            sig = "",
                            kind = TextNoteEvent.KIND
                        ),
                        profile = Profile(
                            publicKey = "pubKey",
                            displayName = "John Doe",
                            nostrEventId = "nostrEventId"
                        ),
                        following = emptyList()
                    )
                ),
                onNavigateToRoute = {},
                onNavigateToChatRoomCreation = {},
                onNavigateToDirectMessageDetail = {},
                nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
            )
        }
    }
}

/**
 * The one action the chat list offers, in whichever slot the layout has for it.
 *
 * Extracted only so that the two branches above cannot drift: on one pane it is the
 * `Scaffold`'s floating action button, on two it sits in the bottom corner of the list
 * pane, and a "new chat" that reads differently depending on window width would be a
 * strange thing to discover.
 */
@Composable
private fun NewChatButton(onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = {
            Icon(
                Icons.Default.Add,
                // The button's own text says "New chat" immediately beside it.
                contentDescription = Decorative,
            )
        },
        text = { Text(stringResource(Res.string.new_chat)) },
    )
}

/** Addresses the chat list pane from a layout test. See `ChatPaneLayoutJvmTest`. */
const val ChatListPaneTag = "chat-list-pane"
