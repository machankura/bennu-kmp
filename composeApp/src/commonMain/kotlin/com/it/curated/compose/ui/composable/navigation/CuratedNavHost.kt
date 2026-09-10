package com.it.curated.compose.ui.composable.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.it.curated.compose.AppLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.it.curated.compose.CuratedGlobal
import com.it.curated.compose.database.repository.DatabaseChatRepository
import com.it.curated.compose.database.repository.DatabaseDkgRepository
import com.it.curated.compose.database.repository.DatabaseFrostSigningRepository
import com.it.curated.compose.database.repository.DatabaseMarmotRepository
import com.it.curated.compose.database.repository.DatabaseNostrRepository
import com.it.curated.compose.database.repository.DatabaseSearchRepository
import com.it.curated.compose.managers.AuxDatabaseManager
import com.it.curated.compose.ui.composable.ActiveProfileScreen
import com.it.curated.compose.ui.composable.AddMemberToChatRoomConfirmationScreen
import com.it.curated.compose.ui.composable.ChatRoomCreationScreen
import com.it.curated.compose.ui.composable.ChatRoomDetailScreen
import com.it.curated.compose.ui.composable.ChatRoomMessagingScreen
import com.it.curated.compose.ui.composable.CreateProfileScreen
import com.it.curated.compose.ui.composable.DkgJoinApprovalScreen
import com.it.curated.compose.ui.composable.DkgRitualScreen
import com.it.curated.compose.ui.composable.DkgRound1ApprovalScreen
import com.it.curated.compose.ui.composable.DkgRound2ApprovalScreen
import com.it.curated.compose.ui.composable.EditGroupNostrProfileScreen
import com.it.curated.compose.ui.composable.AddGroupPostScreen
import com.it.curated.compose.ui.composable.EditGroupRelaysScreen
import com.it.curated.compose.ui.composable.HomeScreen
import com.it.curated.compose.ui.composable.ImplementationPendingScreen
import com.it.curated.compose.ui.composable.KeyPackageManagementScreen
import com.it.curated.compose.ui.composable.KeyRecoveryScreen
import com.it.curated.compose.ui.composable.LandingScreen
import com.it.curated.compose.ui.composable.LoadingScreen
import com.it.curated.compose.ui.composable.NostrEventDetailScreen
import com.it.curated.compose.ui.composable.RecoveryPhraseScreen
import com.it.curated.compose.ui.composable.SearchMemberToAddToChatRoomScreen
import com.it.curated.compose.ui.composable.SearchResultScreen
import com.it.curated.compose.ui.composable.SearchScreen
import com.it.curated.compose.ui.composable.SelectChatRoomMembersScreen
import com.it.curated.compose.ui.composable.SelectChatRoomTypeScreen
import com.it.curated.compose.ui.composable.ShareProfileScreen
import com.it.curated.compose.ui.composable.SignInToProfileScreen
import com.it.curated.compose.ui.composable.SocialPreconditionScreen
import com.it.curated.compose.ui.composable.SovereignWalletStartupScreen
import com.it.curated.compose.ui.composable.UnannouncedProfileScreen
import com.it.curated.compose.ui.composable.UnindexedProfileScreen
import com.it.curated.compose.ui.composable.UnqueuedProfileScreen
import com.it.curated.compose.ui.composable.UnqueuedProfileSynchronizationScreen
import com.it.curated.compose.ui.composable.UnsignedProfileScreen
import com.it.curated.compose.ui.composable.UnsyncedProfileScreen
import com.it.curated.compose.ui.composable.WriteNewNoteScreen
import com.it.curated.compose.ui.composable.navigation.routes.ActiveProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddMemberToChatRoomConfirmationRoute
import com.it.curated.compose.ui.composable.navigation.routes.BlankRoute
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomCreationRoute
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.it.curated.compose.ui.composable.navigation.routes.CreateProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.DkgJoinApprovalRoute
import com.it.curated.compose.ui.composable.navigation.routes.DkgRitualRoute
import com.it.curated.compose.ui.composable.navigation.routes.DkgRound1ApprovalRoute
import com.it.curated.compose.ui.composable.navigation.routes.DkgRound2ApprovalRoute
import com.it.curated.compose.ui.composable.navigation.routes.HomeRoute
import com.it.curated.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.it.curated.compose.ui.composable.navigation.routes.KeyPackageManagementRoute
import com.it.curated.compose.ui.composable.navigation.routes.KeyRecoveryRoute
import com.it.curated.compose.ui.composable.navigation.routes.LandingRoute
import com.it.curated.compose.ui.composable.navigation.routes.LoadingRoute
import com.it.curated.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.RecoveryPhraseRoute
import com.it.curated.compose.ui.composable.navigation.routes.SearchMemberToAddToChatRoomRoute
import com.it.curated.compose.ui.composable.navigation.routes.SearchResultRoute
import com.it.curated.compose.ui.composable.navigation.routes.SearchRoute
import com.it.curated.compose.ui.composable.navigation.routes.SelectChatRoomMembersRoute
import com.it.curated.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import com.it.curated.compose.ui.composable.SelectSubgroupAdminsScreen
import com.it.curated.compose.ui.composable.navigation.routes.SelectChatRoomTypeRoute
import com.it.curated.compose.ui.composable.navigation.routes.ShareProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.SignInRoute
import com.it.curated.compose.ui.composable.navigation.routes.SocialPreconditionRoute
import com.it.curated.compose.ui.composable.navigation.routes.SovereignWalletStartupRoute
import com.it.curated.compose.ui.composable.navigation.routes.UnannouncedProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.UnindexedProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.UnqueuedProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.UnqueuedProfileSynchronizationRoute
import com.it.curated.compose.ui.composable.navigation.routes.UnsignedProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.UnsyncedProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.WriteNewNoteRoute
import com.it.curated.compose.ui.view.model.NavigationViewModel
import com.it.curated.compose.ui.view.model.NotaryViewModel
import com.it.curated.compose.ui.view.model.SovereignWalletViewModel
import com.it.curated.compose.ui.view.model.SynchronizationViewModel
import com.it.curated.compose.ui.view.state.NavigationUIState
import com.it.curated.compose.ui.view.state.NostrEventDetailUIState
import com.it.curated.compose.ui.view.state.SearchUIState
import com.it.curated.compose.ui.theme.NavigationMotion
import com.it.curated.compose.ui.theme.breakpoint
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.it.curated.compose.database.repository.DatabaseMantraRepository
import com.it.curated.compose.ui.composable.AddArtifactScreen
import com.it.curated.compose.ui.composable.navigation.routes.AddArtifactRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddDialectRoute
import com.it.curated.compose.ui.composable.navigation.routes.EditGroupNostrProfileRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddGroupPostRoute
import com.it.curated.compose.ui.composable.navigation.routes.EditGroupRelaysRoute
import com.it.curated.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.it.curated.compose.ui.composable.navigation.routes.ProposalListRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddChapterRoute
import com.it.curated.compose.ui.composable.navigation.routes.AddTranslationRoute
import com.it.curated.compose.ui.composable.navigation.routes.ArtifactDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.ChapterDetailRoute
import com.it.curated.compose.ui.composable.navigation.routes.TranslateChunkRoute
import com.it.curated.compose.ui.composable.navigation.routes.TranslationChapterRoute
import com.it.curated.compose.ui.composable.navigation.routes.TranslationArtifactVersionDetailRoute
import com.it.curated.compose.ui.composable.ArtifactDetailScreen
import com.it.curated.compose.ui.composable.AddChapterScreen
import com.it.curated.compose.ui.composable.AddDialectScreen
import com.it.curated.compose.ui.composable.FrostSigningScreen
import com.it.curated.compose.ui.composable.ProposalListScreen
import com.it.curated.compose.ui.composable.AddTranslationArtifactVersionScreen
import com.it.curated.compose.ui.composable.ChapterDetailScreen
import com.it.curated.compose.ui.composable.TranslateChunkScreen
import com.it.curated.compose.ui.composable.TranslationChapterScreen
import com.it.curated.compose.ui.composable.TranslationArtifactVersionDetailScreen

@Composable
fun CuratedNavHost(
    curatedGlobal: CuratedGlobal,
    phoenixGlobal: PhoenixGlobal,
    navController: NavHostController
) {
    val logger = Logger.withTag("CuratedNavHost")

    val exceptionHandler = remember {
        CoroutineExceptionHandler { _, throwable ->
            logger.e("Caught exception: ${throwable.message}", throwable)
        }
    }

    val applicationIOScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler) }
    val applicationMainScope = remember { CoroutineScope(Dispatchers.Main) }

    val lifecycleOwner = LocalLifecycleOwner.current

    val auxDatabaseManager = remember {
        AuxDatabaseManager(curatedGlobal)
    }

    val databaseNostrRepository = remember {
        DatabaseNostrRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val databaseChatRepository = remember {
        DatabaseChatRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val databaseDkgRepository = remember {
        DatabaseDkgRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val databaseFrostSigningRepository = remember {
        DatabaseFrostSigningRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val databaseMarmotRepository = remember {
        DatabaseMarmotRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }
    val databaseMantraRepository = remember {
        DatabaseMantraRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope
        )
    }

    val searchRepository = remember {
        DatabaseSearchRepository(
            database = auxDatabaseManager.auxDatabase
        )
    }

    val sovereignWalletViewModel: SovereignWalletViewModel = viewModel(
        factory = SovereignWalletViewModel.factory(
            phoenixGlobal = phoenixGlobal
        )
    )

    val navigationViewModel: NavigationViewModel =  viewModel (
        factory = NavigationViewModel.factory(
            activeWallet = sovereignWalletViewModel.activeWalletInUI,
            initialNavigationUIState = NavigationUIState.Loading(),
            nostrRepository = databaseNostrRepository,
            scope = applicationIOScope
        )
    )

    val notaryViewModel: NotaryViewModel = viewModel(
        factory = NotaryViewModel.factory(
            activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
            nostrRepository = databaseNostrRepository,
            chatRepository = databaseChatRepository,
            marmotRepository = databaseMarmotRepository,
            scope = applicationIOScope
        )
    )

    // TODO: Produce a notary UI Element...
    val synchronizationViewModel: SynchronizationViewModel = viewModel(
        factory = SynchronizationViewModel.factory(
            activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
            nostrRepository = databaseNostrRepository,
            chatRepository = databaseChatRepository,
            relayRepository = databaseNostrRepository,
            scope = applicationIOScope
        )
    )

    // TODO: Produce a synchronization UI element...

    // The one place in the app that knows whether it is on screen. Everything that holds a
    // relay connection open reads AppLifecycle rather than a lifecycle owner, because those
    // consumers are application-scoped coroutines that outlive any composition.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> AppLifecycle.enteredForeground()
                Lifecycle.Event.ON_STOP -> AppLifecycle.enteredBackground()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(lifecycleOwner) {
        navigationViewModel.navigationUIState.collect { state ->
            when (state) {
                is NavigationUIState.Error -> {
                    navController.navigate(
                        route = ImplementationPendingRoute(
                            "Error screen"
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.StartupPhoenix -> {
                    navController.navigate(
                        route = SovereignWalletStartupRoute
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.Loading -> {
                    navController.navigate(
                        route = LoadingRoute(
                            text = state.text
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.Landing -> {
                    navController.navigate(
                        route = LandingRoute
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnsignedProfile -> {
                    navController.navigate(
                        route = UnsignedProfileRoute(
                            state.unsignedNostrEvent.id
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnsyncedProfile -> {
                    navController.navigate(
                        route = UnsyncedProfileRoute(
                            state.unsignedNostrEvent.pubKey
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnqueuedProfileSynchronization -> {
                    navController.navigate(
                        route = UnqueuedProfileSynchronizationRoute(
                            unsignedNostrEventId = state.unsignedNostrEvent.id,
                            publicKey = state.unsignedNostrEvent.pubKey
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnindexedProfile -> {
                    navController.navigate(
                        route = UnindexedProfileRoute(
                            state.nostrEvent.id
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.UnqueuedProfile -> {
                    navController.navigate(
                        route = UnqueuedProfileRoute(
                            state.profile.nostrEventId
                        )
                    )
                }
                is NavigationUIState.UnannouncedProfile -> {
                    navController.navigate(
                        route = UnannouncedProfileRoute(
                            state.broadcastNostrEventRequest.nostrEventId
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                is NavigationUIState.ProfileLoaded -> {
                    navController.navigate(
                        route = SocialPreconditionRoute(
                            activeUserPubkey = state.publicKey
                        )
                    ) {
                        popUpTo(0)
                    }
                }
                else -> {
                    navController.navigate(
                        route = ImplementationPendingRoute(
                            "Unsupported $state"
                        )
                    ) {
                        popUpTo(0)
                    }
                }
            }
        }
    }

    // The navigation component's three items, and the two facts it needs to build their
    // routes.
    //
    // `activeUserPublicKey` is read off whichever top-level route is current -- all three
    // carry it -- rather than held here, so it cannot drift from the screen underneath.
    // The profile's metadata event id has no such source: `ActiveProfileRoute` is addressed
    // by event id and only the home screen ever knew one, so it is observed here for as
    // long as a key is signed in. One flow, cancelled and restarted when the key changes.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val activeUserPublicKey = currentBackStackEntry?.destination?.let { destination ->
        when (TopLevelDestination.of(destination)) {
            TopLevelDestination.Messages -> currentBackStackEntry?.toRoute<HomeRoute>()?.activeUserPublicKey
            TopLevelDestination.Search -> currentBackStackEntry?.toRoute<SearchRoute>()?.activeUserPublicKey
            TopLevelDestination.Profile -> currentBackStackEntry?.toRoute<ActiveProfileRoute>()?.activeUserPublicKey
            null -> null
        }
    }

    var activeProfileNostrEventId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeUserPublicKey) {
        val publicKey = activeUserPublicKey ?: return@LaunchedEffect
        databaseNostrRepository.observeProfileWithFollowing(publicKey).collect { profile ->
            activeProfileNostrEventId = profile?.nostrEvent?.id
        }
    }

    CuratedNavigationSuite(
        navController = navController,
        breakpoint = MaterialTheme.breakpoint,
        activeUserPublicKey = activeUserPublicKey,
        activeProfileNostrEventId = activeProfileNostrEventId,
    ) {
        NavHost(
            navController = navController,
            startDestination = LoadingRoute(),
            // All 43 routes at once, which is the point: navigation-compose's default is
            // `fadeIn(tween(700))` in its own internals, so before this the app's
            // transitions were a library's literal rather than a decision, and roughly
            // three times M3's duration for a full-screen change. These come from the
            // theme's MotionScheme and collapse to a fade when the platform asks for
            // reduced motion.
            enterTransition = NavigationMotion.enter(),
            exitTransition = NavigationMotion.exit(),
            popEnterTransition = NavigationMotion.popEnter(),
            popExitTransition = NavigationMotion.popExit(),
        ) {
            composable<SovereignWalletStartupRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SovereignWalletStartupRoute>()

                SovereignWalletStartupScreen(
                    sovereignWalletViewModel = sovereignWalletViewModel,
                    // No seed on this device, so there is no wallet to start: the user has to make or
                    // restore one, and that lives behind the landing screen. This used to park them on
                    // a loading screen with nothing left to load.
                    onNavigateToWalletLandingPage = {
                        navController.navigate(
                            route = LandingRoute
                        ) {
                            popUpTo(0)
                        }
                    },
                    onSuccessfulStartup = {
                        applicationIOScope.launch {
                            navigationViewModel.loadNostrProfile(route)
                        }
                    },
                    forceWalletId = null,
                )
            }
            composable<LoadingRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<LoadingRoute>()

                LoadingScreen(
                    text = route.text
                )
            }
            composable<LandingRoute> {
                LandingScreen(
                    onNavigateToLearnMore = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "Learn more"
                            )
                        )
                    },
                    onNavigateToSignIn = {
                        navController.navigate(
                            route = SignInRoute
                        )
                    },
                    onNavigateToCreateProfile = {
                        navController.navigate(
                            route = CreateProfileRoute()
                        )
                    }
                )
            }
            composable<CreateProfileRoute> {
                CreateProfileScreen(
                    onNavigateToEndThis = {
                        navController.navigate(
                            route = BlankRoute
                        ) {
                            popUpTo(0)
                        }
                    },
                    nostrRepository = databaseNostrRepository,
                    marmotRepository = databaseMarmotRepository,
                    writeSeed = { words, onSeedWritten, onSeedWriteError ->
                        sovereignWalletViewModel.writeSeed(
                            words,
                            isRestoringWallet = false,
                            onSeedWritten = { walletId ->
                                onSeedWritten()

                                sovereignWalletViewModel.loadSovereignData(walletId)
                                sovereignWalletViewModel.listAvailableWallets {
                                    sovereignWalletViewModel.switchToWallet(walletId)
                                    navController.navigate(
                                        route = SovereignWalletStartupRoute
                                    )
                                }
                            },
                            onSeedWriteError = { onSeedWriteError() }
                        )
                    }
                )

            }
            composable<ChatRoomCreationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoomCreationRoute>()

                ChatRoomCreationScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    onNavigateToRoute = { selectMembersRoute ->
                        navController.navigate(
                            selectMembersRoute
                        )
                    }
                )
            }
            composable<SelectChatRoomMembersRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectChatRoomMembersRoute>()

                SelectChatRoomMembersScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    name = route.name,
                    description = route.description,
                    nostrRepository = databaseNostrRepository,
                    onNavigateToRoute = { selectChatRoomTypeRoute ->
                        navController.navigate(
                            selectChatRoomTypeRoute
                        )
                    }
                )
            }
            composable<SelectSubgroupAdminsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectSubgroupAdminsRoute>()

                SelectSubgroupAdminsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    parentChatRoomId = route.parentChatRoomId,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { nextRoute ->
                        navController.navigate(route = nextRoute)
                    }
                )
            }
            composable<DkgRitualRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRitualRoute>()

                DkgRitualScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { approvalRoute ->
                        navController.navigate(route = approvalRoute)
                    }
                )
            }
            composable<DkgJoinApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgJoinApprovalRoute>()

                DkgJoinApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable<DkgRound1ApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRound1ApprovalRoute>()

                DkgRound1ApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable<DkgRound2ApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRound2ApprovalRoute>()

                DkgRound2ApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable<SelectChatRoomTypeRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectChatRoomTypeRoute>()

                SelectChatRoomTypeScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    name = route.name,
                    description = route.description,
                    memberPublicKeys = route.memberPublicKeys,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { chatRoomResultRoute ->
                        navController.navigate(
                            chatRoomResultRoute
                        ) {
                            // Drop every creation step: backing out of the new chat should land
                            // on whatever the user was doing before, not back in the form.
                            popUpTo(
                                ChatRoomCreationRoute(
                                    activeUserPublicKey = route.activeUserPublicKey
                                )
                            ) {
                                inclusive = true
                            }
                        }
                    }
                )
            }
            composable<WriteNewNoteRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<WriteNewNoteRoute>()
                WriteNewNoteScreen(
                    replyToNostrEventId = route.inReplyToEventId,
                    activeUserPublicKey = route.activeUserPublicKey,
                    quotedNostrEventId = route.quotedEventId,
                    onNostrEventPublished = {
                        applicationMainScope.launch {
                            navController.popBackStack()
                        }
                    },
                    onNavigateToNostrEvent = { hexKey ->
                        navController.navigate(
                            route = NostrEventDetailRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                nostrEventId = hexKey
                            )
                        )
                    },
                    nostrRepository = databaseNostrRepository
                )
            }
            composable<SignInRoute> {
                SignInToProfileScreen()
            }
            composable<UnsignedProfileRoute> { backStackEntry ->
                backStackEntry.toRoute<UnsignedProfileRoute>()

                UnsignedProfileScreen()
            }
            composable<UnsyncedProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<UnsyncedProfileRoute>()
                UnsyncedProfileScreen(
                    unsyncedProfilePublicKey = route.publicKey
                )
            }
            composable<UnqueuedProfileSynchronizationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<UnqueuedProfileSynchronizationRoute>()
                UnqueuedProfileSynchronizationScreen(
                    unsignedNostrEventId = route.unsignedNostrEventId,
                    profilePublicKey = route.publicKey,
                    nostrRepository = databaseNostrRepository
                )
            }
            composable<UnindexedProfileRoute> {
                UnindexedProfileScreen()
            }
            composable<UnqueuedProfileRoute> {
                UnqueuedProfileScreen()
            }
            composable<UnannouncedProfileRoute> { backStackEntry ->
                backStackEntry.toRoute<UnannouncedProfileRoute>()

                UnannouncedProfileScreen()
            }
            composable<SocialPreconditionRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SocialPreconditionRoute>()
                SocialPreconditionScreen(
                    onNavigateToSkipForNow = {
                        navController.navigate(
                            route = HomeRoute(
                                activeUserPublicKey = route.activeUserPubkey
                            )
                        )
                    },
                    onNavigateToInviteFriend = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "Invite friend"
                            )
                        )
                    },
                    onNavigateToViewInvites = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "View invites"
                            )
                        )
                    }
                )
            }
            composable<HomeRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<HomeRoute>()
                HomeScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    onNavigateToRoute = { eventRoute ->
                        navController.navigate(
                            route = eventRoute
                        )
                    },
                    onNavigateToChatRoomCreation = {
                        navController.navigate(
                            route = ChatRoomCreationRoute(
                                activeUserPublicKey = route.activeUserPublicKey
                            )
                        )
                    },
                    onNavigateToDirectMessageDetail = { chatRoomDetailRoute ->
                        navController.navigate(
                            route = chatRoomDetailRoute
                        )
                    },
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    dkgRepository = databaseDkgRepository,
                )
            }
            composable<BlankRoute> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // Nothing is drawn on it, so this is a backdrop rather than a surface
                    // carrying content. `scrim` is the role for that, and is #000000 in every
                    // one of this app's schemes -- so the pixels are unchanged and the value
                    // now moves with the theme instead of standing outside it.
                    color = MaterialTheme.colorScheme.scrim
                ) {

                }
            }
            composable<ActiveProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ActiveProfileRoute>()

                ActiveProfileScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    nostrEventId = route.nostrEventId,
                    nostrRepository = databaseNostrRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<ShareProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ShareProfileRoute>()

                ShareProfileScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    nostrEventId = route.nostrEventId,
                    nostrRepository = databaseNostrRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<KeyPackageManagementRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<KeyPackageManagementRoute>()

                KeyPackageManagementScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    nostrEventId = route.nostrEventId,
                    marmotRepository = databaseMarmotRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<KeyRecoveryRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<KeyRecoveryRoute>()

                KeyRecoveryScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<RecoveryPhraseRoute> {
                RecoveryPhraseScreen(
                    phoenixGlobal = phoenixGlobal,
                    activeWalletStateFlow = sovereignWalletViewModel.activeWalletInUI,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<ChatRoomMessagingRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoomMessagingRoute>()

                ChatRoomMessagingScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRouteAndPopUpInclusive = { chatRoomDetailRoute ->
                        navController.navigate(
                            route = chatRoomDetailRoute
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    }
                )
            }
            composable<ChatRoomDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoomDetailRoute>()

                ChatRoomDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    mantraRepository = databaseMantraRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onPopBackToRoute = { popRoute ->
                        navController.popBackStack(
                            route = popRoute,
                            inclusive = false
                        )
                    }
                )
            }
            composable<SearchMemberToAddToChatRoomRoute> {  backStackEntry ->
                val route = backStackEntry.toRoute<SearchMemberToAddToChatRoomRoute>()

                SearchMemberToAddToChatRoomScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    onNavigateToRoute = { chatRoomDetailRoute ->
                        navController.navigate(
                            route = chatRoomDetailRoute
                        )
                    },
                )
            }
            composable<AddMemberToChatRoomConfirmationRoute> {  backStackEntry ->
                val route = backStackEntry.toRoute<AddMemberToChatRoomConfirmationRoute>()

                AddMemberToChatRoomConfirmationScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    profilePublicKey = route.profilePublicKey,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    onInviteSent = {
                        navController.popBackStack(
                            route = SearchMemberToAddToChatRoomRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                chatRoomId = route.chatRoomId,
                                relayHint = route.relayHint
                            ),
                            inclusive = true
                        )
                    },
                )
            }
            composable<AddArtifactRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AddArtifactRoute>()

                AddArtifactScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    mantraRepository = databaseMantraRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace this add screen so back returns to the group rather
                        // than to a form whose proposal has already gone out.
                        navController.navigate(
                            route = signingRoute
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    }
                )
            }
            composable<AddDialectRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AddDialectRoute>()

                AddDialectScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace this add screen so back returns to the group rather
                        // than to a form whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<AddDialectRoute> {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    }
                )
            }
            composable<EditGroupNostrProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<EditGroupNostrProfileRoute>()

                EditGroupNostrProfileScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the form so back returns to the group rather than
                        // to an edit whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<EditGroupNostrProfileRoute> {
                                inclusive = true
                            }
                        }
                    }
                )
            }
            composable<EditGroupRelaysRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<EditGroupRelaysRoute>()

                EditGroupRelaysScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the editor so back returns to the group rather
                        // than to lists whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<EditGroupRelaysRoute> {
                                inclusive = true
                            }
                        }
                    }
                )
            }
            composable<AddGroupPostRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AddGroupPostRoute>()

                AddGroupPostScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the composer so back returns to the group rather
                        // than to a draft whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<AddGroupPostRoute> {
                                inclusive = true
                            }
                        }
                    }
                )
            }
            composable<FrostSigningRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<FrostSigningRoute>()

                FrostSigningScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    sessionId = route.sessionId,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<ProposalListRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ProposalListRoute>()

                ProposalListScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToRoute = { signingRoute ->
                        navController.navigate(route = signingRoute)
                    }
                )
            }
            composable<ArtifactDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ArtifactDetailRoute>()

                ArtifactDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    artifactId = route.artifactId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<ChapterDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChapterDetailRoute>()

                ChapterDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chapterId = route.chapterId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<TranslationArtifactVersionDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TranslationArtifactVersionDetailRoute>()

                TranslationArtifactVersionDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    translationArtifactVersionId = route.translationArtifactVersionId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<TranslationChapterRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TranslationChapterRoute>()

                TranslationChapterScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    translationChapterId = route.translationChapterId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<TranslateChunkRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TranslateChunkRoute>()

                TranslateChunkScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    translationChapterId = route.translationChapterId,
                    chunkId = route.chunkId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace this editor so back returns to the chapter table
                        // rather than to a form whose proposal has already gone out.
                        // The table itself is left alone: nothing is translated until
                        // the group signs, so there is nothing new for it to show.
                        navController.navigate(route = signingRoute) {
                            popUpTo<TranslateChunkRoute> {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<AddTranslationRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AddTranslationRoute>()

                AddTranslationArtifactVersionScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    artifactId = route.artifactId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace this screen so back returns to the artifact rather
                        // than to a form whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<AddTranslationRoute> {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<AddChapterRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AddChapterRoute>()

                AddChapterScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    artifactId = route.artifactId,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    mantraRepository = databaseMantraRepository,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace this add screen so back returns to the artifact
                        // rather than to a form whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<AddChapterRoute> {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(
                            route = actionRoute
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SearchRoute>()

                SearchScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    initialSearchUIState = SearchUIState.Prompt,
                    nostrRepository = databaseNostrRepository,
                    searchRepository = searchRepository,
                    onNavigateToProfile = { route ->
                        navController.navigate(
                            route = route
                        )
                    },
                    onNavigateToSearchResult = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<SearchResultRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SearchResultRoute>()

                SearchResultScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    searchQuery = route.query,
                    nostrRepository = databaseNostrRepository,
                    searchRepository = searchRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToEvent = { route ->
                        navController.navigate(
                            route = route
                        )
                    }
                )
            }
            composable<NostrEventDetailRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<NostrEventDetailRoute>()

                NostrEventDetailScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    initialNostrEventDetailUIState = NostrEventDetailUIState.Loading,
                    nostrEventId = route.nostrEventId,
                    nostrRepository = databaseNostrRepository,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onNavigateToEvent = { route ->
                        navController.navigate(
                            route = route
                        )
                    },
                    onNavigateToWriteAReply = { nostrEventId ->
                        navController.navigate(
                            route = WriteNewNoteRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                inReplyToEventId = nostrEventId
                            )
                        )
                    },
                    onNavigateToEditProfile = {
                        navController.navigate(
                            route = ImplementationPendingRoute(
                                "Edit profile"
                            )
                        )
                    },
                    onNavigateToQuoteNostrEvent = { nostrEventId ->
                        navController.navigate(
                            route = WriteNewNoteRoute(
                                activeUserPublicKey = route.activeUserPublicKey,
                                quotedEventId = nostrEventId
                            )
                        )
                    },
                    onNavigateToDirectMessage = { chatRoom ->
                        navController.navigate(
                            route = chatRoom
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                )
            }
            composable<ImplementationPendingRoute> { backStackEntry ->
                val route: ImplementationPendingRoute = backStackEntry.toRoute()
                ImplementationPendingScreen(
                    route.name
                )
            }
        }
    }

}