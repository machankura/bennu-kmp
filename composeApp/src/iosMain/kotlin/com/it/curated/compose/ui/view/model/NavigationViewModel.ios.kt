package com.it.curated.compose.ui.view.model

import com.it.curated.compose.AppVersion
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.DecryptSeedResult
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.ios.BusinessManager
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.security.EncryptedSeed
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch


actual fun updateBusinessActiveInUI(walletId: WalletId) {
    BusinessManager.updateBusinessActiveInUI(walletId)
}

actual fun loadAndDecryptSeed(phoenixGlobal: PhoenixGlobal): DecryptSeedResult {
    return SeedManager.loadAndDecrypt(
        phoenixGlobal
    )
}

actual fun getAvailableWalletsMeta(phoenixGlobal: PhoenixGlobal): Flow<Map<WalletId, UserWalletMetadata>> {
    return DataStoreManager(
        phoenixGlobal.ctx,
        chain = Chain.Mainnet
    ).loadGlobalPrefsForWallet().getAvailableWalletsMeta
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    metadata: UserWalletMetadata
) {
    DataStoreManager(
        phoenixGlobal.ctx,
        chain = Chain.Mainnet
    ).loadGlobalPrefsForWallet().saveAvailableWalletMeta(metadata)
}

actual suspend fun saveAvailableWalletMeta(
    phoenixGlobal: PhoenixGlobal,
    walletId: WalletId,
    name: String?,
    avatar: String,
    isHidden: Boolean
) {
    DataStoreManager(
        phoenixGlobal.ctx,
        chain = Chain.Mainnet
    ).loadGlobalPrefsForWallet().saveAvailableWalletMeta(
        walletId = walletId,
        name = name,
        avatar = avatar,
        isHidden = isHidden
    )
}

actual fun platformWriteSeed(
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
) {
    if (writingState !is WritingSeedState.Init) return
    viewModelScope.launch(Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        log.e("failed to write mnemonics to disk: ${e.message}")
        onWritingSeedError.invoke(
            WritingSeedState.Error.Generic(e)
        )
    }) {
        log.d("writing mnemonics to disk...")

        onWritingSeedStateWriting.invoke(
            WritingSeedState.Writing(mnemonics)
        )
        val existingSeeds = SeedManager.loadAndDecryptOrNull(phoenixGlobal)?.map {
            it.key to it.value.words
        }?.toMap()

        val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
        val keyManager = LocalKeyManager(seed, NodeParamsManager.chain, NodeParamsManager.remoteSwapInXpub)
        val newWalletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)

        when {
            existingSeeds == null -> {
                log.e("could not load the existing seed map, aborting...")
                onWritingSeedError.invoke(
                    WritingSeedState.Error.CannotLoadSeedMap
                )
                return@launch
            }
            existingSeeds.containsKey(newWalletId) -> {
                log.i("attempting to import a seed that already exists, aborting...")
                onWritingSeedError.invoke(
                    WritingSeedState.Error.SeedAlreadyExists
                )
                return@launch
            }
            else -> {
                val newSeedMap = existingSeeds + (newWalletId to mnemonics)
                val encrypted = EncryptedSeed.V2.encrypt(newSeedMap)
                SeedManager.writeSeedToDisk(phoenixGlobal, encrypted, overwrite = true)
                if (isRestoringWallet) {
                    log.i("successfully restored wallet=$newWalletId")
                } else {
                    log.i("successfully created wallet=$newWalletId")
                }
            }
        }

        globalPrefs.saveLastUsedAppCode(AppVersion.versionCode)
        val dataStoreManager = DataStoreManager(
            phoenixGlobal.ctx,
            chain = NodeParamsManager.chain,
        )
        val userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId = newWalletId)
        userPrefs.saveIsTorEnabled(isTorEnabled)
        userPrefs.saveElectrumServer(customElectrumServer)

        viewModelScope.launch(Dispatchers.Main) {
            delay(1000)
            onSeedWritten(newWalletId)
        }
    }
}