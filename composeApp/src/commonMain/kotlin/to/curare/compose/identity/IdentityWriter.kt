package to.curare.compose.identity

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.DataStoreManager
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.NostrKeyManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.EncryptedNostrKeys
import fr.acinq.phoenix.security.EncryptedSeed
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.curare.compose.AppVersion
import to.curare.compose.ui.view.model.WritingSeedState

/**
 * Puts a new identity's secret on disk, one function per kind, and prepares the
 * per-identity preferences both kinds read.
 *
 * [writeMnemonic] was an `expect` with three byte-identical actuals -- android, jvm, ios --
 * using nothing but commonMain: SeedManager, EncryptedSeed, LocalKeyManager,
 * DataStoreManager, AppVersion. It was an expect because something once needed to
 * differ and nothing does now, so it is one function here, and the second writer sits
 * next to it rather than being triplicated in turn.
 *
 * Both refuse a duplicate **by nostr public key**, not only by id. A wallet and an
 * imported key can be the same npub under different ids -- one is hash160 of the node
 * key, the other hash160 of the nostr key -- and the database is keyed by pubkey, so two
 * identities for one pubkey would share every row and disagree about which is active.
 */
object IdentityWriter {

    /** The nostr public keys of every identity already on this device, or null if a store could not be read. */
    private fun knownNostrPublicKeys(phoenixGlobal: PhoenixGlobal): Set<HexKey>? {
        val wallets = SeedManager.loadAndDecryptOrNull(phoenixGlobal) ?: return null
        val nostrKeys = NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal) ?: return null
        return StoredIdentity.merge(wallets, nostrKeys).values.map { it.nostrPublicKey }.toSet()
    }

    /**
     * Creates the preference files a new id needs and records the choices made before
     * the id was known. Tor and a custom Electrum server mean nothing to an identity
     * without a wallet; they are saved anyway, because `loadUserPrefsForWallet` is what
     * creates the file and the recovery screens read it.
     */
    private suspend fun prepareIdentity(
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        id: WalletId,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ) {
        globalPrefs.saveLastUsedAppCode(AppVersion.versionCode)
        val dataStoreManager = DataStoreManager(
            phoenixGlobal.ctx,
            chain = NodeParamsManager.chain,
        )
        val userPrefs = dataStoreManager.loadUserPrefsForWallet(walletId = id)
        userPrefs.saveIsTorEnabled(isTorEnabled)
        userPrefs.saveElectrumServer(customElectrumServer)
    }

    /**
     * Adds [mnemonics] to `seed.dat`. Callback-shaped, driving [WritingSeedState], because
     * that is what the create flow and its view model were built around.
     */
    fun writeMnemonic(
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
            // Null when nostr-keys.dat exists and cannot be read; an empty map when it is absent.
            val existingNostrKeys = NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal)

            val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
            val keyManager = LocalKeyManager(seed, NodeParamsManager.chain, NodeParamsManager.remoteSwapInXpub)
            val newWalletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)
            val newNostrPublicKey = keyManager.nostrPrivateKey().nostrPublicKeyHex()

            when {
                existingSeeds == null || existingNostrKeys == null -> {
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
                existingNostrKeys.containsKey(newNostrPublicKey) -> {
                    // The same npub is already here as a bare key. The id check above cannot
                    // see that -- different hash, different key -- so it is asked by pubkey.
                    log.i("attempting to import a seed whose nostr key is already here, aborting...")
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

            prepareIdentity(phoenixGlobal, globalPrefs, newWalletId, isTorEnabled, customElectrumServer)

            viewModelScope.launch(Dispatchers.Main) {
                delay(1000)
                onSeedWritten(newWalletId)
            }
        }
    }

    sealed class WriteNostrKeyResult {
        data class Written(val id: WalletId) : WriteNostrKeyResult()

        /** An identity with this nostr public key is already on the device, as a wallet or as a key. */
        data object AlreadyExists : WriteNostrKeyResult()

        /** One of the two stores exists and could not be read; nothing was written. */
        data object CannotLoadKeys : WriteNostrKeyResult()
    }

    /**
     * Adds [privateKey] to `nostr-keys.dat` and prepares its preferences. Suspending and
     * result-shaped rather than callback-shaped: nothing was built around a state machine
     * for it, and the caller is a coroutine already. Throws on an I/O or key store failure
     * during the write, as [writeMnemonic]'s handler would have caught.
     */
    suspend fun writeNostrKey(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        privateKey: PrivateKey,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ): WriteNostrKeyResult {
        log.d("writing nostr key to disk...")
        val stored = StoredIdentity.nostrSecret(privateKey)

        val known = knownNostrPublicKeys(phoenixGlobal)
        val existingNostrKeys = NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal)
        if (known == null || existingNostrKeys == null) {
            log.e("could not load the existing keys, aborting...")
            return WriteNostrKeyResult.CannotLoadKeys
        }
        if (stored.nostrPublicKey in known) {
            log.i("attempting to import a nostr key that is already here, aborting...")
            return WriteNostrKeyResult.AlreadyExists
        }

        val encrypted = EncryptedNostrKeys.encrypt(existingNostrKeys + (stored.nostrPublicKey to privateKey))
        NostrKeyManager.writeToDisk(phoenixGlobal, encrypted)
        log.i("successfully imported nostr key for identity=${stored.id}")

        prepareIdentity(phoenixGlobal, globalPrefs, stored.id, isTorEnabled, customElectrumServer)

        return WriteNostrKeyResult.Written(stored.id)
    }

    sealed class ForgetNostrKeyResult {
        data object Forgotten : ForgetNostrKeyResult()

        /** The key is not in `nostr-keys.dat`: a mnemonic identity's key lives in the seed, and removing a seed is a wallet question. */
        data object NotABareKey : ForgetNostrKeyResult()

        data object CannotLoadKeys : ForgetNostrKeyResult()
    }

    /**
     * The inverse of [writeNostrKey]: removes the key from `nostr-keys.dat` and deletes
     * the identity's preference files. The profile stays on the relays, and the same key
     * can be signed in again. Only for a bare key -- see [ForgetNostrKeyResult.NotABareKey].
     */
    suspend fun forgetNostrKey(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        id: WalletId,
        nostrPublicKey: HexKey,
    ): ForgetNostrKeyResult {
        val existing = NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal)
        if (existing == null) {
            log.e("could not load the existing keys, aborting...")
            return ForgetNostrKeyResult.CannotLoadKeys
        }
        if (!existing.containsKey(nostrPublicKey)) {
            log.i("asked to forget a key that is not a bare key on this device")
            return ForgetNostrKeyResult.NotABareKey
        }

        NostrKeyManager.writeToDisk(phoenixGlobal, EncryptedNostrKeys.encrypt(existing - nostrPublicKey))
        log.i("forgot nostr key for identity=$id")

        DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain).deleteNodeUserPrefs(id)

        return ForgetNostrKeyResult.Forgotten
    }
}
