package to.curare.compose.ui.view.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptNostrKeysResult
import fr.acinq.phoenix.managers.NostrKeyManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.curare.compose.extensions.hexToNsecHrp
import to.curare.compose.identity.Identity
import to.curare.compose.identity.IdentityKind
import to.curare.compose.identity.IdentityWriter
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.NostrSecretUIState

/**
 * Reads back the nostr secret key an identity was signed in with.
 *
 * The counterpart of [RecoveryPhraseViewModel] for the identity kind that has a key and
 * no phrase. There is no wallet behind such an identity, so the key is the whole of the
 * backup, and everything here is about getting it in front of the user once and
 * recording that they say they stored it.
 *
 * The key is read from the file at reveal time, not off the active identity, so the
 * screen's contract -- nothing secret held longer than it is shown -- is the same as the
 * phrase screen's. The backup flags are the same per-identity preferences the phrase
 * screen writes; they mean "this identity's secret is not backed up" for whichever
 * secret it is.
 *
 * It also owns the inverse of an import: [forgetKey], which removes the key from the
 * device and the account with it. Only for a bare key; a mnemonic identity's key is in
 * the seed, and removing a seed is a wallet question this does not answer.
 */
class NostrSecretViewModel(
    val phoenixGlobal: PhoenixGlobal,
    val activeIdentityStateFlow: StateFlow<Identity?>,
    private val nostrRepository: NostrRepository,
    private val forgetNostrKey: suspend (Identity) -> IdentityWriter.ForgetNostrKeyResult,
    private val hideIdentityMetadata: suspend (Identity) -> Unit,
) : ViewModel() {

    private val logger = Logger.withTag(TAG)

    private val _uiState = MutableStateFlow<NostrSecretUIState>(NostrSecretUIState.Hidden)
    val uiState = _uiState.asStateFlow()

    private val _forgetting = MutableStateFlow<NostrSecretUIState.Forgetting>(NostrSecretUIState.Forgetting.Idle)
    val forgetting = _forgetting.asStateFlow()

    /** Whether the active identity is one whose key this screen can show and forget. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isBareKeyIdentity: StateFlow<Boolean> = activeIdentityStateFlow
        .flatMapLatest { flowOf(it?.kind == IdentityKind.NostrSecret) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isBackupDone: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isManualSeedBackupDone ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isDisclaimerRead: StateFlow<Boolean?> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.isSeedLossDisclaimerRead ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val showBackupNotice: StateFlow<Boolean> = activeIdentityStateFlow
        .flatMapLatest { it?.internalPrefs?.showSeedBackupNotice ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun revealNostrSecret() {
        if (_uiState.value is NostrSecretUIState.Revealing) return

        val identity = activeIdentityStateFlow.value
        if (identity == null) {
            logger.e { "no active identity, cannot read a nostr key" }
            _uiState.value = NostrSecretUIState.Error.NoActiveIdentity
            return
        }

        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("failed to read the nostr keys", throwable)
            _uiState.value = NostrSecretUIState.Error.KeysUnreadable
        }) {
            _uiState.value = NostrSecretUIState.Revealing

            // Keystore-backed, and it reads a file: it blocks, so it cannot run on the main thread.
            val result = withContext(Dispatchers.IO) {
                NostrKeyManager.loadAndDecrypt(phoenixGlobal)
            }

            _uiState.value = when (result) {
                is DecryptNostrKeysResult.Success -> {
                    val privateKey = result.keys[identity.nostrPublicKey]
                    if (privateKey == null) {
                        logger.e { "key file holds no key for identity=${identity.id}" }
                        NostrSecretUIState.Error.NoKeyForThisIdentity
                    } else {
                        NostrSecretUIState.Revealed(privateKey.value.toHex().hexToNsecHrp())
                    }
                }
                is DecryptNostrKeysResult.Failure.FileNotFound -> {
                    logger.e { "no key file, so no key for identity=${identity.id}" }
                    NostrSecretUIState.Error.NoKeyForThisIdentity
                }
                is DecryptNostrKeysResult.Failure -> {
                    logger.e { "unable to read the nostr keys: $result" }
                    NostrSecretUIState.Error.KeysUnreadable
                }
            }
        }
    }

    fun hideNostrSecret() {
        _uiState.value = NostrSecretUIState.Hidden
    }

    fun setBackupDone(isDone: Boolean) {
        val internalPrefs = activeIdentityStateFlow.value?.internalPrefs ?: return
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("could not save the backup flag", throwable)
        }) {
            internalPrefs.saveManualSeedBackupDone(isDone)
        }
    }

    fun setDisclaimerRead(isRead: Boolean) {
        val internalPrefs = activeIdentityStateFlow.value?.internalPrefs ?: return
        viewModelScope.launch(CoroutineExceptionHandler { _, throwable ->
            logger.e("could not save the disclaimer flag", throwable)
        }) {
            internalPrefs.saveSeedLossDisclaimerRead(isRead)
        }
    }

    fun askToForgetKey() {
        _forgetting.value = NostrSecretUIState.Forgetting.Confirming
    }

    fun cancelForgetKey() {
        _forgetting.value = NostrSecretUIState.Forgetting.Idle
    }

    /**
     * Key out of the file, preferences deleted, metadata hidden, account rows gone -- in
     * that order, so a failure partway leaves the key on disk rather than an identity the
     * selector lists but nothing can open. [onForgotten] runs on the main thread once the
     * device no longer holds the key; the caller clears the active identity and re-lists.
     */
    fun forgetKey(onForgotten: () -> Unit) {
        val identity = activeIdentityStateFlow.value ?: return
        if (_forgetting.value is NostrSecretUIState.Forgetting.InProgress) return
        _forgetting.value = NostrSecretUIState.Forgetting.InProgress
        _uiState.value = NostrSecretUIState.Hidden

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            logger.e("could not forget the key", throwable)
            _forgetting.value = NostrSecretUIState.Forgetting.Failed
        }) {
            when (forgetNostrKey(identity)) {
                is IdentityWriter.ForgetNostrKeyResult.Forgotten -> {
                    hideIdentityMetadata(identity)
                    nostrRepository.forgetLocalAccount(identity.nostrPublicKey)
                    _forgetting.value = NostrSecretUIState.Forgetting.Idle
                    withContext(Dispatchers.Main) { onForgotten() }
                }
                is IdentityWriter.ForgetNostrKeyResult.NotABareKey,
                is IdentityWriter.ForgetNostrKeyResult.CannotLoadKeys -> {
                    _forgetting.value = NostrSecretUIState.Forgetting.Failed
                }
            }
        }
    }

    companion object {
        private const val TAG = "NostrSecretViewModel"

        fun factory(
            phoenixGlobal: PhoenixGlobal,
            activeIdentityStateFlow: StateFlow<Identity?>,
            nostrRepository: NostrRepository,
            forgetNostrKey: suspend (Identity) -> IdentityWriter.ForgetNostrKeyResult,
            hideIdentityMetadata: suspend (Identity) -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NostrSecretViewModel(
                    phoenixGlobal = phoenixGlobal,
                    activeIdentityStateFlow = activeIdentityStateFlow,
                    nostrRepository = nostrRepository,
                    forgetNostrKey = forgetNostrKey,
                    hideIdentityMetadata = hideIdentityMetadata,
                )
            }
        }
    }
}
