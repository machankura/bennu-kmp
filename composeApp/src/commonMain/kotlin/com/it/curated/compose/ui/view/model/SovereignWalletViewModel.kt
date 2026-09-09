package com.it.curated.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.lightning.logging.error
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.ActiveWallet
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.data.ListWalletState
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class WritingSeedState {
    data object Init : WritingSeedState()
    data class Writing(val mnemonics: List<String>) : WritingSeedState()
    data class WrittenToDisk(val walletId: WalletId) : WritingSeedState()
    sealed class Error : WritingSeedState() {
        data class Generic(val cause: Throwable) : Error()
        data object CannotLoadSeedMap: Error()
        data object SeedAlreadyExists: Error()
    }
}

expect fun updateBusinessActiveInUI(walletId: WalletId)
expect fun loadAndDecryptSeed(phoenixGlobal: PhoenixGlobal): DecryptSeedResult

expect fun getAvailableWalletsMeta(phoenixGlobal: PhoenixGlobal): Flow<Map<WalletId, UserWalletMetadata>>

expect suspend fun saveAvailableWalletMeta(phoenixGlobal: PhoenixGlobal, metadata: UserWalletMetadata)

expect suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    walletId: WalletId,
    name: String?,
    avatar: String,
    isHidden: Boolean
)

class SovereignWalletViewModel(
    val phoenixGlobal: PhoenixGlobal,
    // We might end up only using the machankuraWalletRepository in the future where we send the walletId in each request.
): ViewModel() {
    private val log = Logger.withTag("SovereignWalletViewModel")

    // A StateFlow, not a `mutableStateOf`, and it has to stay one. This view model is built by
    // `viewModel()` during composition, so a Compose state created in its constructor is created
    // inside that composition's snapshot -- and a write from another thread that lands before the
    // composition is applied is discarded. `listAvailableWallets` runs from `init` and, on a device
    // with no seed file, answers in about four milliseconds, which is squarely inside that window:
    // the write to Success was lost and the app sat on "Decrypting..." for ever. A StateFlow has no
    // snapshot to belong to, so the same write from the same thread at the same moment is seen.
    private val _listWalletState = MutableStateFlow<ListWalletState>(ListWalletState.Init)
    val listWalletState = _listWalletState.asStateFlow()

    private val _availableWallets = MutableStateFlow<Map<WalletId, UserWallet>>(emptyMap())
    val availableWallets = _availableWallets.asStateFlow()

    private val _desiredWalletId = MutableStateFlow<WalletId?>(null)
    val desiredWalletId = _desiredWalletId.asStateFlow()
    val startWalletImmediately = MutableStateFlow(true)

    private val _activeWalletInUI = MutableStateFlow<ActiveWallet?>(null)
    val activeWalletInUI = _activeWalletInUI.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val exchangeRates = phoenixGlobal.currencyManager.ratesFlow
        .stateIn(viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList())

    var walletUIState: com.it.curated.compose.ui.view.state.SovereignWalletUIState by mutableStateOf(
        com.it.curated.compose.ui.view.state.SovereignWalletUIState.Loading)
        private set

    fun retryLoad() {
        walletUIState = com.it.curated.compose.ui.view.state.SovereignWalletUIState.Loading
    }


    fun loadSovereignData(walletId: WalletId) {
        walletUIState = com.it.curated.compose.ui.view.state.SovereignWalletUIState.Success(
            publicKey = walletId.nodeIdHash,
            balanceInSatoshis = 0 // TODO: Get balance...
        )

        viewModelScope.launch {

        }
    }


    init {
        listAvailableWallets(onDone = {})
    }

    fun setActiveWallet(walletId: WalletId, business: PhoenixBusiness) {
        val dataStoreManager = DataStoreManager(business)

        val userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId = walletId)
        val internalPrefs = dataStoreManager.loadInternalPrefsForWallet( walletId = walletId)
        _activeWalletInUI.value = ActiveWallet(id = walletId, business = business, userPrefs = userPrefs, internalPrefs = internalPrefs)
        updateBusinessActiveInUI(walletId)
//        scheduleAutoLock()
    }

    fun listAvailableWallets(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
//            log.error("error when initialising startup-view: ", e)
            _listWalletState.value = ListWalletState.Error.Generic(e)
        }) {

            when (val result = loadAndDecryptSeed(phoenixGlobal)) {
                is DecryptSeedResult.Failure.SerializationError -> {
                    log.error {"cannot deserialize seed file: "}
                    _listWalletState.value = ListWalletState.Error.Serialization
                }
                is DecryptSeedResult.Failure.DecryptionError -> {
                    log.e("cannot decrypt seed file: ", throwable = result.cause)
                    _listWalletState.value = ListWalletState.Error.DecryptionError.GeneralException(result.cause)
                }
                is DecryptSeedResult.Failure.KeyStoreFailure -> {
                    log.e("key store failure: ", throwable = result.cause)
                    _listWalletState.value = ListWalletState.Error.DecryptionError.KeystoreFailure(result.cause)
                }
                is DecryptSeedResult.Failure.SeedFileUnreadable -> {
                    log.e("aborting, unreadable seed file")
                    _listWalletState.value = ListWalletState.Error.Generic(null)
                }
                is DecryptSeedResult.Failure.SeedInvalid -> {
                    log.e("aborting, seed is invalid")
                    _listWalletState.value = ListWalletState.Error.Generic(null)
                }

                is DecryptSeedResult.Failure.SeedFileNotFound -> {
                    _listWalletState.value = ListWalletState.Success
                    _availableWallets.value = emptyMap()
                }

                is DecryptSeedResult.Success -> {

                    val metadataMap = getAvailableWalletsMeta(phoenixGlobal).first()
                    result.userWalletsMap.forEach { (walletId, _) ->
                        if (metadataMap[walletId] == null) {
                            saveAvailableWalletMeta(
                                phoenixGlobal = phoenixGlobal,
                                walletId = walletId,
                                name = null,
                                avatar = com.it.curated.compose.ui.composable.widgets.wallet.WalletAvatars.list.random(),
                                isHidden = false
                            )
                        }
                    }
                    _availableWallets.value = result.userWalletsMap
                    _listWalletState.value = ListWalletState.Success
                    viewModelScope.launch(Dispatchers.Main) {
                        onDone()
                    }
                }
            }
        }
    }

//    fun scheduleAutoLock() {
//        // TODO: Run this through an expect...
//        viewModelScope.launch {
//            autoLockHandler.removeCallbacksAndMessages(null)
//            val activeUserPrefs = activeWalletInUI.first()?.userPrefs ?: return@launch
//
//            val biometricLockEnabled = activeUserPrefs.getLockBiometricsEnabled.first()
//            val customPinLockEnabled = activeUserPrefs.getLockPinEnabled.first()
//            val autoLockDelay = activeUserPrefs.getAutoLockDelay.first()
//
//            if ((biometricLockEnabled || customPinLockEnabled) && autoLockDelay != Duration.INFINITE) {
//                autoLockHandler.postDelayed(autoLockRunnable, autoLockDelay.inWholeMilliseconds)
//            }
//        }
//    }

    /** Clears the active wallet and signals the startup screen to load the given [walletId]. */
    fun switchToWallet(walletId: WalletId) {
        _desiredWalletId.value = walletId
        _activeWalletInUI.value = null
    }

    /** Clears the active wallet. It does not affect [desiredWalletId]. The UI may still auto-open a specific wallet, if [desiredWalletId] is not null. */
    fun clearActiveWallet() {
        _activeWalletInUI.value = null
    }

    /** Resets the active wallet and [desiredWalletId]. The UI will redirect to the startup screen with the wallets selector prompt. */
    fun resetToSelector() {
        _desiredWalletId.value = null
        _activeWalletInUI.value = null
        startWalletImmediately.value = false
    }

    override fun onCleared() {
        super.onCleared()
        log.i("AppViewModel cleared")
    }

    fun getGlobalPrefs(): GlobalPrefs {
        return com.it.curated.compose.extensions.getGlobalPrefs(
            phoenixGlobal
        )
    }

    // wallet initialisation options -- to be saved to user prefs once the wallet has been created and we know its walletId
    var isTorEnabled = mutableStateOf(false)
    var customElectrumServer = mutableStateOf<ElectrumConfig.Custom?>(null)

    /** Monitors the writing of a seed on disk ; used by the restore view and the create view thru [writeSeed]. */
    var writingState by mutableStateOf<WritingSeedState>(WritingSeedState.Init)
        private set


    /**
     * Attempts to write a seed on disk and updates the view model state. If a seed already
     * exists on disk, this method will put the [writingState] in error.
     */
    fun writeSeed(
        mnemonics: List<String>,
        isRestoringWallet: Boolean,
        onSeedWritten: (WalletId) -> Unit,
        onSeedWriteError: (WritingSeedState.Error) -> Unit = {}
    ) {
        if (writingState is WritingSeedState.Error || writingState is WritingSeedState.WrittenToDisk) {
            // A previous attempt finished; reset so platformWriteSeed does not silently skip this one.
            writingState = WritingSeedState.Init
        }
        platformWriteSeed(
            log = log,
            phoenixGlobal = phoenixGlobal,
            globalPrefs = getGlobalPrefs(),
            writingState = writingState,
            viewModelScope = viewModelScope,
            mnemonics = mnemonics,
            onWritingSeedError = { writingSeedStateError ->
                writingState = writingSeedStateError
                onSeedWriteError(writingSeedStateError)
            },
            onWritingSeedStateWriting = { writing ->
                writingState = writing
            },
            isRestoringWallet = isRestoringWallet,
            isTorEnabled = isTorEnabled.value,
            customElectrumServer = customElectrumServer.value,
            onSeedWritten = { walletId ->
                writingState = WritingSeedState.WrittenToDisk(walletId)
                onSeedWritten(walletId)
            }
        )
    }

    companion object {
        private const val TAG = "SovereignWalletViewModel"
        fun factory(
            phoenixGlobal: PhoenixGlobal,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SovereignWalletViewModel(
                    phoenixGlobal = phoenixGlobal,
                )
            }
        }
    }
}

expect fun platformWriteSeed(
    log: Logger,
    phoenixGlobal: PhoenixGlobal,
    globalPrefs: GlobalPrefs,
    writingState: WritingSeedState,
    viewModelScope: CoroutineScope,
    mnemonics: List<String>,
    onWritingSeedError: (WritingSeedState.Error) -> Unit,
    onWritingSeedStateWriting: (WritingSeedState.Writing) -> Unit,
    isRestoringWallet: Boolean,
    isTorEnabled: Boolean,
    customElectrumServer: ElectrumConfig.Custom?,
    onSeedWritten: (WalletId) -> Unit
)
