package com.it.curated.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.it.curated.compose.database.model.MarmotKeyPackageBundle
import com.it.curated.compose.repository.MarmotRepository
import com.it.curated.compose.ui.view.state.KeyPackageManagementUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class KeyPackageManagementViewModel(
    val activeUserPublicKey: HexKey,
    val nostrEventId: HexKey,
    initialActiveProfileUIState: KeyPackageManagementUIState,
    val marmotRepository: MarmotRepository
): ViewModel() {
    var keyPackageManagementUIState: KeyPackageManagementUIState by mutableStateOf(initialActiveProfileUIState)
        private set

    init {
        loadNostrFeed()
    }

    fun loadNostrFeed() {
        viewModelScope.launch(Dispatchers.IO) {
            marmotRepository.observeMarmotKeyPackageBundles(activeUserPublicKey).distinctUntilChanged().collect { keyPackageBundles ->
                keyPackageManagementUIState = KeyPackageManagementUIState.Loaded(
                    keyPackageBundles = keyPackageBundles
                )
            }
        }
    }

    /**
     * @param onDone called on completion so the screen can report it. Both of these were
     *   fire and forget: you tapped, a coroutine ran, and nothing on screen changed --
     *   which is indistinguishable from a tap that missed.
     */
    fun publishNewKeyPackage(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // TODO: Rotate all other keys...
            marmotRepository.publishMarmotKeyPackageBundle(
                publicKey = activeUserPublicKey,
                nsecPassword = "" // TODO: Implement nsecPassword logic...
            )
            onDone()
        }
    }

    fun rotateKeyPackage(
        marmotKeyPackageBundle: MarmotKeyPackageBundle,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            marmotRepository.rotateMarmotKeyPackageBundle(marmotKeyPackageBundle)
            onDone()
        }
    }

    companion object {
        const val TAG = "KeyPackageManagementViewModel"

        fun factory(
            nostrEventId: HexKey,
            activeUserPublicKey: HexKey,
            initialActiveProfileUIState: KeyPackageManagementUIState = KeyPackageManagementUIState.Loading,
            marmotRepository: MarmotRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                KeyPackageManagementViewModel(
                    nostrEventId = nostrEventId,
                    initialActiveProfileUIState = initialActiveProfileUIState,
                    marmotRepository = marmotRepository,
                    activeUserPublicKey = activeUserPublicKey
                )
            }
        }
    }
}