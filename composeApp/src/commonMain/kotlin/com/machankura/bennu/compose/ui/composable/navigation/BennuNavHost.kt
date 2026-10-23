package com.machankura.bennu.compose.ui.composable.navigation

import com.machankura.bennu.compose.ui.composable.GroupSendScreen
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupSendRoute
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.machankura.bennu.compose.AppLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import com.machankura.bennu.compose.BennuGlobal
import com.machankura.bennu.compose.database.repository.DatabaseChatRepository
import com.machankura.bennu.compose.database.repository.DatabaseDkgRepository
import com.machankura.bennu.compose.database.repository.DatabaseFrostSigningRepository
import com.machankura.bennu.compose.database.repository.DatabaseMarmotRepository
import com.machankura.bennu.compose.database.repository.DatabaseNostrRepository
import com.machankura.bennu.compose.database.repository.DatabaseSearchRepository
import com.machankura.bennu.compose.managers.AuxDatabaseManager
import com.machankura.bennu.compose.ui.composable.ActiveProfileScreen
import com.machankura.bennu.compose.ui.composable.AddMemberToChatRoomConfirmationScreen
import com.machankura.bennu.compose.ui.composable.ChatRoomCreationScreen
import com.machankura.bennu.compose.ui.composable.ChatRoomDetailScreen
import com.machankura.bennu.compose.ui.composable.ChatRoomMessagingScreen
import com.machankura.bennu.compose.ui.composable.CreateProfileScreen
import com.machankura.bennu.compose.ui.composable.DkgJoinApprovalScreen
import com.machankura.bennu.compose.ui.composable.DkgRitualScreen
import com.machankura.bennu.compose.ui.composable.DkgRound1ApprovalScreen
import com.machankura.bennu.compose.ui.composable.DkgRound2ApprovalScreen
import com.machankura.bennu.compose.ui.composable.EditGroupNostrProfileScreen
import com.machankura.bennu.compose.ui.composable.AddGroupPostScreen
import com.machankura.bennu.compose.ui.composable.BroadcastGroupSignedEventScreen
import com.machankura.bennu.compose.ui.composable.ProposeGroupEventScreen
import com.machankura.bennu.compose.ui.composable.EditGroupRelaysScreen
import com.machankura.bennu.compose.ui.composable.GroupNostrProfileScreen
import com.machankura.bennu.compose.ui.composable.GroupRelaysScreen
import com.machankura.bennu.compose.ui.composable.GroupWalletScreen
import com.machankura.bennu.compose.ui.composable.GroupWalletAddressesScreen
import com.machankura.bennu.compose.ui.composable.GroupPostsScreen
import com.machankura.bennu.compose.ui.composable.HomeScreen
import com.machankura.bennu.compose.ui.composable.ImplementationPendingScreen
import com.machankura.bennu.compose.ui.composable.KeyPackageManagementScreen
import com.machankura.bennu.compose.ui.composable.KeyRecoveryScreen
import com.machankura.bennu.compose.ui.composable.LandingScreen
import com.machankura.bennu.compose.ui.composable.LoadingScreen
import com.machankura.bennu.compose.ui.composable.NostrEventDetailScreen
import com.machankura.bennu.compose.ui.composable.NostrSecretScreen
import com.machankura.bennu.compose.ui.composable.RecoveryPhraseScreen
import com.machankura.bennu.compose.ui.composable.SearchMemberToAddToChatRoomScreen
import com.machankura.bennu.compose.ui.composable.SearchResultScreen
import com.machankura.bennu.compose.ui.composable.SearchScreen
import com.machankura.bennu.compose.ui.composable.SelectChatRoomMembersScreen
import com.machankura.bennu.compose.ui.composable.SelectChatRoomTypeScreen
import com.machankura.bennu.compose.ui.composable.ShareProfileScreen
import com.machankura.bennu.compose.ui.composable.SignInToProfileScreen
import com.machankura.bennu.compose.ui.composable.SocialPreconditionScreen
import com.machankura.bennu.compose.ui.composable.SovereignWalletStartupScreen
import com.machankura.bennu.compose.ui.composable.UnannouncedProfileScreen
import com.machankura.bennu.compose.ui.composable.UnindexedProfileScreen
import com.machankura.bennu.compose.ui.composable.UnqueuedProfileScreen
import com.machankura.bennu.compose.ui.composable.UnqueuedProfileSynchronizationScreen
import com.machankura.bennu.compose.ui.composable.UnsignedProfileScreen
import com.machankura.bennu.compose.ui.composable.UnsyncedProfileScreen
import com.machankura.bennu.compose.ui.composable.WriteNewNoteScreen
import com.machankura.bennu.compose.ui.composable.navigation.routes.ActiveProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.AddMemberToChatRoomConfirmationRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.BlankRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ChatRoomCreationRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ChatRoomDetailRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.CreateProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.DkgJoinApprovalRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.DkgRitualRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.DkgRound1ApprovalRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.DkgRound2ApprovalRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.HomeRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.KeyPackageManagementRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.KeyRecoveryRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.LandingRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.LoadingRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.NostrEventDetailRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.NostrSecretRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ProfilesRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.RecoveryPhraseRoute
import com.machankura.bennu.compose.ui.composable.ProfilePreviewScreen
import com.machankura.bennu.compose.ui.composable.ProfilesScreen
import com.machankura.bennu.compose.ui.view.model.ProfilesViewModel
import com.machankura.bennu.compose.ui.view.model.getAvailableWalletsMeta
import com.machankura.bennu.compose.ui.view.model.bareKeyProfileWriter
import com.machankura.bennu.compose.ui.view.model.seedProfileWriter
import com.machankura.bennu.compose.ui.composable.navigation.routes.SearchMemberToAddToChatRoomRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SearchResultRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SearchRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SelectChatRoomMembersRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import com.machankura.bennu.compose.ui.composable.SelectSubgroupAdminsScreen
import com.machankura.bennu.compose.ui.composable.navigation.routes.SelectChatRoomTypeRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ShareProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SignInRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SocialPreconditionRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.SovereignWalletStartupRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.UnannouncedProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.UnindexedProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.UnqueuedProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.UnqueuedProfileSynchronizationRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.UnsignedProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.UnsyncedProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.WriteNewNoteRoute
import com.machankura.bennu.compose.ui.view.model.NavigationViewModel
import com.machankura.bennu.compose.ui.view.model.NotaryViewModel
import com.machankura.bennu.compose.ui.view.model.SovereignWalletViewModel
import com.machankura.bennu.compose.ui.view.model.SynchronizationViewModel
import com.machankura.bennu.compose.ui.view.state.NavigationUIState
import com.machankura.bennu.compose.ui.view.state.NostrEventDetailUIState
import com.machankura.bennu.compose.ui.view.state.SearchUIState
import com.machankura.bennu.compose.ui.theme.NavigationMotion
import com.machankura.bennu.compose.ui.composable.SignOutDependencies
import com.machankura.bennu.compose.ui.composable.widgets.ProvideSigningCapability
import com.machankura.bennu.compose.ui.theme.breakpoint
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.machankura.bennu.compose.database.repository.DatabaseMantraRepository
import com.machankura.bennu.compose.ui.composable.AddArtifactScreen
import com.machankura.bennu.compose.ui.composable.navigation.routes.AddArtifactRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.AddDialectRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.EditGroupNostrProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.AddGroupPostRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ProposeGroupEventRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.EditGroupRelaysRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupNostrProfileRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupRelaysRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupWalletRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupWalletAddressesRoute
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import com.machankura.bennu.compose.wallet.GroupTransactionWatcher
import fr.acinq.phoenix.managers.NodeParamsManager
import com.machankura.bennu.compose.ui.composable.navigation.routes.GroupPostsRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.FrostSigningRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ProposalListRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.AddChapterRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.AddTranslationRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ArtifactDetailRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.ChapterDetailRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.TranslateChunkRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.TranslationChapterRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.TranslationArtifactVersionDetailRoute
import com.machankura.bennu.compose.ui.composable.ArtifactDetailScreen
import com.machankura.bennu.compose.ui.composable.AddChapterScreen
import com.machankura.bennu.compose.ui.composable.AddDialectScreen
import com.machankura.bennu.compose.ui.composable.FrostSigningScreen
import com.machankura.bennu.compose.ui.composable.ProposalListScreen
import com.machankura.bennu.compose.ui.composable.AddTranslationArtifactVersionScreen
import com.machankura.bennu.compose.ui.composable.ChapterDetailScreen
import com.machankura.bennu.compose.ui.composable.TranslateChunkScreen
import com.machankura.bennu.compose.ui.composable.TranslationChapterScreen
import com.machankura.bennu.compose.ui.composable.TranslationArtifactVersionDetailScreen

@Composable
fun BennuNavHost(
    bennuGlobal: BennuGlobal,
    phoenixGlobal: PhoenixGlobal,
    navController: NavHostController
) {
    val logger = Logger.withTag("BennuNavHost")

    val exceptionHandler = remember {
        CoroutineExceptionHandler { _, throwable ->
            logger.e("Caught exception: ${throwable.message}", throwable)
        }
    }

    val applicationIOScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler) }
    val applicationMainScope = remember { CoroutineScope(Dispatchers.Main) }

    val lifecycleOwner = LocalLifecycleOwner.current

    val auxDatabaseManager = remember {
        AuxDatabaseManager(bennuGlobal)
    }

    // Before the repositories: the nostr repository stamps every fetch request it queues
    // with the active identity, and takes the flow at construction.
    val sovereignWalletViewModel: SovereignWalletViewModel = viewModel(
        factory = SovereignWalletViewModel.factory(
            phoenixGlobal = phoenixGlobal
        )
    )

    val databaseNostrRepository = remember {
        DatabaseNostrRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
        )
    }

    val databaseChatRepository = remember {
        DatabaseChatRepository(
            database = auxDatabaseManager.auxDatabase,
            applicationIOScope,
            activeIdentity = sovereignWalletViewModel.activeIdentity,
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

    // The group wallets this identity is watching, on its own node's Electrum
    // client -- null for an nsec or npub profile, which has no node and so no
    // chain to watch. Remade with the node and stopped with it, because a switch
    // of profile is a restart and a wallet left subscribing on the last one's
    // connection is a wallet nobody can see.
    val activeIdentityForWallets by sovereignWalletViewModel.activeIdentity.collectAsState()
    val groupOnchainWallets = remember(activeIdentityForWallets?.business) {
        activeIdentityForWallets?.business?.let { GroupOnchainWallets(it.electrumClient, applicationIOScope) }
    }
    DisposableEffect(groupOnchainWallets) {
        onDispose { groupOnchainWallets?.let { wallets -> applicationIOScope.launch { wallets.stop() } } }
    }

    // Sends the group's signed spends to the network from this device too, and
    // follows them to a confirmation -- whether or not anybody opens the wallet.
    // Same lifetime as the wallets: the identity's node, and nothing longer.
    val groupTransactionWatcher = remember(activeIdentityForWallets?.business) {
        activeIdentityForWallets?.business?.let {
            GroupTransactionWatcher(auxDatabaseManager.auxDatabase, it.electrumClient, applicationIOScope).also { watcher -> watcher.start() }
        }
    }
    DisposableEffect(groupTransactionWatcher) {
        onDispose { groupTransactionWatcher?.stop() }
    }

    val navigationViewModel: NavigationViewModel =  viewModel (
        factory = NavigationViewModel.factory(
            activeIdentity = sovereignWalletViewModel.activeIdentity,
            initialNavigationUIState = NavigationUIState.Loading(),
            nostrRepository = databaseNostrRepository,
            scope = applicationIOScope
        )
    )

    val notaryViewModel: NotaryViewModel = viewModel(
        factory = NotaryViewModel.factory(
            activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
            nostrRepository = databaseNostrRepository,
            chatRepository = databaseChatRepository,
            marmotRepository = databaseMarmotRepository,
            scope = applicationIOScope
        )
    )

    // TODO: Produce a notary UI Element...
    val synchronizationViewModel: SynchronizationViewModel = viewModel(
        factory = SynchronizationViewModel.factory(
            activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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

    // What signing out of a read-only identity takes, for the two screens that offer it.
    // The tail is the nsec forget's: the device no longer holds anything for the
    // identity, so re-list -- the selector drops it -- then clear the active identity,
    // and the navigation observer sends a null identity to startup, which shows the
    // selector or, if this was the last one, Landing.
    val signOutOfReadOnlyIdentity = remember(sovereignWalletViewModel) {
        SignOutDependencies(
            activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
            forgetNostrCredential = { identity -> sovereignWalletViewModel.forgetNostrCredential(identity) },
            hideIdentityMetadata = { identity -> sovereignWalletViewModel.hideIdentityMetadata(identity) },
            onSignedOut = {
                sovereignWalletViewModel.forgetDefaultIdentity()
                sovereignWalletViewModel.listIdentities {
                    sovereignWalletViewModel.resetToSelector()
                }
            },
        )
    }

    // Once, above every screen: what a read-only identity may not be offered is decided
    // where the control is drawn, and this is how the control finds out.
    ProvideSigningCapability(sovereignWalletViewModel.activeIdentity) {
    BennuNavigationSuite(
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
                    nostrRepository = databaseNostrRepository,
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
                    onNavigateToSignIn = {
                        navController.navigate(route = SignInRoute)
                    },
                    onNavigateToCreateProfile = {
                        navController.navigate(route = CreateProfileRoute(withWallet = false))
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
                    // The first profile on a device is the one the wallet will belong to.
                    onNavigateToCreateProfile = {
                        navController.navigate(
                            route = CreateProfileRoute(withWallet = true)
                        )
                    }
                )
            }
            composable<CreateProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<CreateProfileRoute>()
                CreateProfileScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    nostrRepository = databaseNostrRepository,
                    marmotRepository = databaseMarmotRepository,
                    // A seed from Landing, a bare key from inside: the screen does not know
                    // which. See docs/multiple-profiles.md, Phase 5.
                    newProfile = if (route.withWallet) sovereignWalletViewModel.seedProfileWriter() else sovereignWalletViewModel.bareKeyProfileWriter(),
                    // The sign-in tail, with the popUpTo(0) it always had there: back from the
                    // new profile's startup must not return to a form whose secret is already
                    // on disk. The account rows are in the database before this runs, so the
                    // machine finds them the moment the identity is activated.
                    onProfileCreated = { walletId ->
                        sovereignWalletViewModel.loadSovereignData(walletId)
                        sovereignWalletViewModel.listIdentities {
                            sovereignWalletViewModel.switchToIdentity(walletId)
                            navController.navigate(
                                route = SovereignWalletStartupRoute
                            ) {
                                popUpTo(0)
                            }
                        }
                    },
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<SelectSubgroupAdminsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SelectSubgroupAdminsRoute>()

                SelectSubgroupAdminsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    parentChatRoomId = route.parentChatRoomId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { nextRoute ->
                        navController.navigate(route = nextRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<DkgRitualRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgRitualRoute>()

                DkgRitualScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    chatRepository = databaseChatRepository,
                    dkgRepository = databaseDkgRepository,
                    onNavigateToRoute = { approvalRoute ->
                        navController.navigate(route = approvalRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<DkgJoinApprovalRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<DkgJoinApprovalRoute>()

                DkgJoinApprovalScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    dkgSessionId = route.dkgSessionId,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    nostrRepository = databaseNostrRepository
                )
            }
            composable<SignInRoute> {
                SignInToProfileScreen(
                    nostrRepository = databaseNostrRepository,
                    writeNostrKey = { privateKey -> sovereignWalletViewModel.writeNostrKey(privateKey) },
                    writeNostrPublicKey = { publicKey -> sovereignWalletViewModel.writeNostrPublicKey(publicKey) },
                    writeRecoveryPhrase = { words, onWritten, onError ->
                        sovereignWalletViewModel.writeSeed(
                            words,
                            isRestoringWallet = true,
                            onSeedWritten = onWritten,
                            onSeedWriteError = onError,
                        )
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    // The tail the create flow uses after writeSeed: re-list, select, go to
                    // startup. Startup finds the new identity, activates it, and the navigation
                    // machine takes it from the placeholder account the sign-in planted. The
                    // form is popped so that back does not return to a field holding a secret.
                    //
                    // The navigate here looks redundant with the observer's -- switching clears
                    // the identity, and a null identity is StartupPhoenix, which the observer
                    // above navigates to with the same popUpTo(0). It is not: the navigation
                    // state is a StateFlow, and an update to a state equal to the current one
                    // emits nothing. This is what guarantees the stack moves even on the day
                    // the state does not. Leave both.
                    onSignedIn = { walletId ->
                        sovereignWalletViewModel.loadSovereignData(walletId)
                        sovereignWalletViewModel.listIdentities {
                            sovereignWalletViewModel.switchToIdentity(walletId)
                            navController.navigate(
                                route = SovereignWalletStartupRoute
                            ) {
                                popUpTo(0)
                            }
                        }
                    },
                )
            }
            composable<UnsignedProfileRoute> { backStackEntry ->
                backStackEntry.toRoute<UnsignedProfileRoute>()

                UnsignedProfileScreen()
            }
            composable<UnsyncedProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<UnsyncedProfileRoute>()
                val identities by sovereignWalletViewModel.availableIdentities.collectAsState()
                UnsyncedProfileScreen(
                    unsyncedProfilePublicKey = route.publicKey,
                    nostrRepository = databaseNostrRepository,
                    signOut = signOutOfReadOnlyIdentity,
                    // Another profile to go to, when there is one; the switcher has a back
                    // button, so this screen is still there if the user changes their mind.
                    onSwitchProfile = if (identities.size > 1) {
                        { navController.navigate(route = ProfilesRoute) }
                    } else null,
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
                    },
                    signOut = signOutOfReadOnlyIdentity,
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
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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
            composable<ProfilesRoute> {
                val profilesViewModel: ProfilesViewModel = viewModel(
                    factory = ProfilesViewModel.factory(
                        listWalletState = sovereignWalletViewModel.listWalletState,
                        availableIdentities = sovereignWalletViewModel.availableIdentities,
                        activeIdentity = sovereignWalletViewModel.activeIdentity,
                        walletsMetadata = getAvailableWalletsMeta(phoenixGlobal),
                        nostrRepository = databaseNostrRepository,
                    ),
                )
                ProfilesScreen(
                    viewModel = profilesViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    // The switch is the whole of it: the observer above sends the null
                    // identity to startup, and startup opens the one named.
                    onSwitch = { walletId ->
                        sovereignWalletViewModel.switchToIdentity(walletId)
                    },
                    onRetry = {
                        sovereignWalletViewModel.listIdentities {}
                    },
                    onSignIn = {
                        navController.navigate(route = SignInRoute)
                    },
                    onCreateProfile = {
                        navController.navigate(route = CreateProfileRoute(withWallet = false))
                    },
                )
            }
            composable<KeyRecoveryRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<KeyRecoveryRoute>()

                KeyRecoveryScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
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
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<NostrSecretRoute> {
                NostrSecretScreen(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = sovereignWalletViewModel.activeIdentity,
                    nostrRepository = databaseNostrRepository,
                    forgetNostrCredential = { identity -> sovereignWalletViewModel.forgetNostrCredential(identity) },
                    hideIdentityMetadata = { identity -> sovereignWalletViewModel.hideIdentityMetadata(identity) },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    // The device no longer holds the key. Re-list so the selector drops it,
                    // then clear the active identity: the navigation observer sends a null
                    // identity to startup, which shows the selector -- or Landing, if this
                    // was the last one.
                    onForgotten = {
                        sovereignWalletViewModel.forgetDefaultIdentity()
                        sovereignWalletViewModel.listIdentities {
                            sovereignWalletViewModel.resetToSelector()
                        }
                    },
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
                    },
                    // A pushed route, unlike the same screen inside the home screen's
                    // detail pane, so there is something to pop.
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    onNavigateBack = {
                        navController.popBackStack()
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
                    // Back is to the search, not past it: a member who picked the wrong
                    // person wants the list again, not the room.
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupNostrProfileRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupNostrProfileRoute>()

                GroupNostrProfileScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupRelaysRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupRelaysRoute>()

                GroupRelaysScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupWalletRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupWalletRoute>()

                GroupWalletScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    wallets = groupOnchainWallets,
                    watcher = groupTransactionWatcher,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupWalletAddressesRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupWalletAddressesRoute>()

                GroupWalletAddressesScreen(
                    chatRoomId = route.chatRoomId,
                    chain = NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    wallets = groupOnchainWallets,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupSendRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupSendRoute>()

                GroupSendScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    chain = NodeParamsManager.chain,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    wallets = groupOnchainWallets,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the form so back returns to the wallet rather
                        // than to a spend that has already been proposed.
                        navController.navigate(route = signingRoute) {
                            popUpTo<GroupSendRoute> { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<GroupPostsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<GroupPostsRoute>()

                GroupPostsScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    onNavigateToRoute = { actionRoute ->
                        navController.navigate(route = actionRoute)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<BroadcastGroupSignedEventRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<BroadcastGroupSignedEventRoute>()

                BroadcastGroupSignedEventScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    signedEventId = route.signedEventId,
                    chatRepository = databaseChatRepository,
                    // Handed the pool itself rather than a queue to write to,
                    // as the entries screen is for its "Broadcast all": a
                    // group-signed event must not become a `NostrEvent` row, and
                    // the queue hangs off one. See
                    // `BroadcastGroupSignedEventViewModel`.
                    publishTransport = synchronizationViewModel.relaysSocketManager,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable<ProposeGroupEventRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ProposeGroupEventRoute>()

                ProposeGroupEventScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    chatRoomId = route.chatRoomId,
                    relayHint = route.relayHint,
                    chatRepository = databaseChatRepository,
                    frostSigningRepository = databaseFrostSigningRepository,
                    onNavigateToRouteAndPopUpInclusive = { signingRoute ->
                        // Replace the paste so back returns to the group rather
                        // than to an event whose proposal has already gone out.
                        navController.navigate(route = signingRoute) {
                            popUpTo<ProposeGroupEventRoute> {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
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
                    },
                    initialFilter = route.filter
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
            composable<ProfilePreviewRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ProfilePreviewRoute>()

                ProfilePreviewScreen(
                    activeUserPublicKey = route.activeUserPublicKey,
                    profilePublicKey = route.profilePublicKey,
                    nostrRepository = databaseNostrRepository,
                    chatRepository = databaseChatRepository,
                    // The room replaces the preview, as it replaces the profile detail
                    // above: back from a chat is the list, not the person it was started
                    // from.
                    onStartChat = { chatRoom ->
                        navController.navigate(
                            route = chatRoom
                        ) {
                            popUpTo(route) {
                                inclusive = true
                            }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                )
            }
            composable<ImplementationPendingRoute> { backStackEntry ->
                val route: ImplementationPendingRoute = backStackEntry.toRoute()

                // Pushed from a "learn more" or an "edit profile", and also where the
                // navigation observer above lands with `popUpTo(0)` on an error. Whether
                // there is anything under this entry is what decides the back button, and
                // it is remembered because the answer is read again while the screen is
                // leaving, when the stack has already moved on.
                val hasSomethingToReturnTo = remember {
                    navController.previousBackStackEntry != null
                }

                ImplementationPendingScreen(
                    text = route.name,
                    onNavigateBack = if (hasSomethingToReturnTo) {
                        { navController.popBackStack() }
                    } else {
                        null
                    }
                )
            }
        }
    }
    }

}