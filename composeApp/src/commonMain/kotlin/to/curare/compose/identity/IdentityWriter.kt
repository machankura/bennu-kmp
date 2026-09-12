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
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.SeedManager
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.EncryptedNostrCredentials
import fr.acinq.phoenix.security.EncryptedSeed
import fr.acinq.phoenix.security.NostrCredential
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
 * Puts a new identity on disk, one function per kind, and prepares the per-identity
 * preferences every kind reads.
 *
 * [writeMnemonic] was an `expect` with three byte-identical actuals -- android, jvm, ios --
 * using nothing but commonMain: SeedManager, EncryptedSeed, LocalKeyManager,
 * DataStoreManager, AppVersion. It was an expect because something once needed to
 * differ and nothing does now, so it is one function here, and the second writer sits
 * next to it rather than being triplicated in turn.
 *
 * All three refuse a duplicate **by nostr public key**, not only by id. A wallet and an
 * imported key can be the same npub under different ids -- one is hash160 of the node
 * key, the other hash160 of the nostr key -- and the database is keyed by pubkey, so two
 * identities for one pubkey would share every row and disagree about which is active.
 *
 * With one exception, which is the point of the credentials file: a public key already
 * here read-only is not a duplicate of the secret that signs as it. The device does not
 * have that secret, and refusing it would be false. [writeNostrKey] replaces the entry
 * in one write; [writeMnemonic] has to span two files and says which goes first.
 */
object IdentityWriter {

    /** What the seed store says, or null if it could not be read. */
    private fun seedPublicKeys(phoenixGlobal: PhoenixGlobal): Set<HexKey>? =
        SeedManager.loadAndDecryptOrNull(phoenixGlobal)
            ?.values
            ?.map { StoredIdentity.nostrPublicKeyOf(it.words) }
            ?.toSet()

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
            // Null when nostr-credentials.dat exists and cannot be read; an empty map when it is absent.
            val existingCredentials = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)

            val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector()
            val keyManager = LocalKeyManager(seed, NodeParamsManager.chain, NodeParamsManager.remoteSwapInXpub)
            val newWalletId = WalletId(keyManager.nodeKeys.nodeKey.publicKey)
            val newNostrPublicKey = keyManager.nostrPrivateKey().nostrPublicKeyHex()

            when {
                existingSeeds == null || existingCredentials == null -> {
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
                existingCredentials[newNostrPublicKey] is NostrCredential.Secret -> {
                    // The same npub is already here as a bare key. The id check above cannot
                    // see that -- different hash, different key -- so it is asked by pubkey.
                    log.i("attempting to import a seed whose nostr key is already here, aborting...")
                    onWritingSeedError.invoke(
                        WritingSeedState.Error.SeedAlreadyExists
                    )
                    return@launch
                }
                else -> {
                    if (existingCredentials[newNostrPublicKey] is NostrCredential.Public) {
                        // The npub is here read-only, and the words that sign as it have just
                        // been pasted: an upgrade, across two files. The credential goes
                        // first, then the seed. A crash between the two loses the read-only
                        // identity, which the npub pasted again restores; the reverse order
                        // would leave one npub listed twice under two ids.
                        log.i("the seed's nostr key is here read-only; replacing it with the wallet")
                        NostrCredentialManager.writeToDisk(
                            phoenixGlobal,
                            EncryptedNostrCredentials.encrypt(existingCredentials - newNostrPublicKey),
                        )
                    }
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

    sealed class WriteNostrCredentialResult {
        data class Written(val id: WalletId) : WriteNostrCredentialResult()

        /**
         * An identity with this nostr public key is already on the device and holds at least
         * what was pasted: a wallet or a secret, for either input; a public key, for an npub.
         */
        data object AlreadyExists : WriteNostrCredentialResult()

        /** One of the two stores exists and could not be read; nothing was written. */
        data object CannotLoadKeys : WriteNostrCredentialResult()
    }

    /**
     * Adds [privateKey] to `nostr-credentials.dat` as a secret and prepares its
     * preferences. Suspending and result-shaped rather than callback-shaped: nothing was
     * built around a state machine for it, and the caller is a coroutine already. Throws
     * on an I/O or key store failure during the write, as [writeMnemonic]'s handler would
     * have caught.
     *
     * A public entry for the same key is the one thing that is not a duplicate: the entry
     * becomes a secret one, in a single write, under the id it already had.
     */
    suspend fun writeNostrKey(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        privateKey: PrivateKey,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ): WriteNostrCredentialResult {
        log.d("writing nostr key to disk...")
        val stored = StoredIdentity.nostrSecret(privateKey)

        val seeds = seedPublicKeys(phoenixGlobal)
        val existing = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        if (seeds == null || existing == null) {
            log.e("could not load the existing keys, aborting...")
            return WriteNostrCredentialResult.CannotLoadKeys
        }
        if (stored.nostrPublicKey in seeds || existing[stored.nostrPublicKey] is NostrCredential.Secret) {
            log.i("attempting to import a nostr key that is already here, aborting...")
            return WriteNostrCredentialResult.AlreadyExists
        }
        val upgrading = existing[stored.nostrPublicKey] is NostrCredential.Public

        val encrypted = EncryptedNostrCredentials.encrypt(
            existing + (stored.nostrPublicKey to NostrCredential.Secret(privateKey))
        )
        NostrCredentialManager.writeToDisk(phoenixGlobal, encrypted)
        if (upgrading) {
            log.i("read-only identity=${stored.id} now holds its key")
        } else {
            log.i("successfully imported nostr key for identity=${stored.id}")
        }

        prepareIdentity(phoenixGlobal, globalPrefs, stored.id, isTorEnabled, customElectrumServer)

        return WriteNostrCredentialResult.Written(stored.id)
    }

    /**
     * Adds [nostrPublicKey] to `nostr-credentials.dat` as a public entry -- an identity
     * the device can look at and not sign as -- and prepares its preferences. Refused if
     * the device already holds anything at all for the key, since anything is more than
     * this.
     */
    suspend fun writeNostrPublicKey(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        globalPrefs: GlobalPrefs,
        nostrPublicKey: HexKey,
        isTorEnabled: Boolean,
        customElectrumServer: ElectrumConfig.Custom?,
    ): WriteNostrCredentialResult {
        log.d("writing nostr public key to disk...")
        val stored = StoredIdentity.nostrPublic(nostrPublicKey)

        val seeds = seedPublicKeys(phoenixGlobal)
        val existing = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        if (seeds == null || existing == null) {
            log.e("could not load the existing keys, aborting...")
            return WriteNostrCredentialResult.CannotLoadKeys
        }
        if (stored.nostrPublicKey in seeds || existing.containsKey(stored.nostrPublicKey)) {
            log.i("attempting to add a public key the device already holds something for, aborting...")
            return WriteNostrCredentialResult.AlreadyExists
        }

        NostrCredentialManager.writeToDisk(
            phoenixGlobal,
            EncryptedNostrCredentials.encrypt(existing + (stored.nostrPublicKey to NostrCredential.Public)),
        )
        log.i("added read-only identity=${stored.id}")

        prepareIdentity(phoenixGlobal, globalPrefs, stored.id, isTorEnabled, customElectrumServer)

        return WriteNostrCredentialResult.Written(stored.id)
    }

    sealed class ForgetNostrCredentialResult {
        data object Forgotten : ForgetNostrCredentialResult()

        /** The key is not in `nostr-credentials.dat`: a mnemonic identity's key lives in the seed, and removing a seed is a wallet question. */
        data object NotACredential : ForgetNostrCredentialResult()

        data object CannotLoadKeys : ForgetNostrCredentialResult()
    }

    /**
     * The inverse of [writeNostrKey] and [writeNostrPublicKey] alike: removes the entry,
     * whichever kind it is, from `nostr-credentials.dat` and deletes the identity's
     * preference files. The profile stays on the relays, and the same key can be signed
     * in again. Only for a credential -- see [ForgetNostrCredentialResult.NotACredential].
     */
    suspend fun forgetNostrCredential(
        log: Logger,
        phoenixGlobal: PhoenixGlobal,
        id: WalletId,
        nostrPublicKey: HexKey,
    ): ForgetNostrCredentialResult {
        val existing = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)
        if (existing == null) {
            log.e("could not load the existing keys, aborting...")
            return ForgetNostrCredentialResult.CannotLoadKeys
        }
        if (!existing.containsKey(nostrPublicKey)) {
            log.i("asked to forget a key that is not a credential on this device")
            return ForgetNostrCredentialResult.NotACredential
        }

        NostrCredentialManager.writeToDisk(phoenixGlobal, EncryptedNostrCredentials.encrypt(existing - nostrPublicKey))
        log.i("forgot nostr credential for identity=$id")

        DataStoreManager(phoenixGlobal.ctx, chain = NodeParamsManager.chain).deleteNodeUserPrefs(id)

        return ForgetNostrCredentialResult.Forgotten
    }
}
