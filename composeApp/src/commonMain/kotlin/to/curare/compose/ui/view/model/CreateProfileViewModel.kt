package to.curare.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import to.curare.compose.repository.MarmotRepository
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.CreateProfileUIState
import to.curare.compose.ui.view.state.form.CreateProfileFormState
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.nsecPassword
import fr.acinq.phoenix.utils.MnemonicLanguage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.curare.compose.extensions.nostrPublicKey
import kotlin.time.Duration.Companion.seconds

class CreateProfileViewModel(
    val initialCreateProfileUIState: CreateProfileUIState,
    val createProfileFormState: CreateProfileFormState = CreateProfileFormState(),
    val nostrRepository: NostrRepository,
    val marmotRepository: MarmotRepository
): ViewModel() {
    companion object {
        private const val TAG = "CreateAccountViewModel"

        fun factory(
            initialCreateProfileUIState: CreateProfileUIState,
            nostrRepository: NostrRepository,
            marmotRepository: MarmotRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CreateProfileViewModel(
                    initialCreateProfileUIState,
                    nostrRepository = nostrRepository,
                    marmotRepository = marmotRepository
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    var createProfileUIState: MutableState<CreateProfileUIState> = mutableStateOf(
        initialCreateProfileUIState
    )

    fun validateInput(): Boolean {
        if (createProfileFormState.nameField.textFieldState.text.isBlank()) {
            createProfileFormState.nameField.errorMessage.value = "Please input a display name"
            return false
        } else {
            createProfileFormState.nameField.errorMessage.value = null
        }

        if (createProfileFormState.biographyField.textFieldState.text.isBlank()) {
            createProfileFormState.biographyField.errorMessage.value = "Please input a biography"
            return false
        } else {
            createProfileFormState.biographyField.errorMessage.value = null
        }

        return true
    }


    private fun failProfileCreation(e: Throwable? = null) {
        if (e != null) {
            logger.e("error when creating new profile: ", e)
        } else {
            logger.e { "seed write failed, aborting profile creation" }
        }
        createProfileUIState.value = CreateProfileUIState.Error
        isActionPending.value = false
    }

    fun createAccount(
        writeSeed: (mnemonics: List<String>, onSeedWritten: () -> Unit, onSeedWriteError: () -> Unit) -> Unit
    ) {
        logger.d { "createAccount" }
        if (isActionPending.value) return
        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            failProfileCreation(e)
        }) {
            logger.d("generating new wallet...")
            val entropy = Lightning.randomBytes(16)
            val mnemonics = MnemonicCode.toMnemonics(
                entropy = entropy,
                wordlist = MnemonicLanguage.English.wordlist()
            )

            val localKeyManager = LocalKeyManager(
                seed = MnemonicCode.toSeed(mnemonics, "").byteVector(),
                chain = NodeParamsManager.chain,
                remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub
            )

            val pubkey = localKeyManager.nostrPublicKey()

            logger.d("Pubkey: $pubkey" )

            // Only create the profile events once the seed backing this pubkey is safely
            // on disk; otherwise the notary could never sign them.
            writeSeed(
                mnemonics,
                {
                    viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                        failProfileCreation(e)
                    }) {
                        nostrRepository.createNewProfile(
                            pubkey,
                            name = createProfileFormState.nameField.textFieldState.text.toString(),
                            biography = createProfileFormState.biographyField.textFieldState.text.toString(),
                            onCompletion = {
                                logger.d("Created: $pubkey" )
                            }
                        )
                    }
                },
                { failProfileCreation() }
            )
        }
    }

    fun wipeDatabase(
        onNavigateToEndThis: () -> Unit
    ) {
        viewModelScope.launch {
            nostrRepository.wipeDatabase()
            onNavigateToEndThis.invoke()
        }
    }
}