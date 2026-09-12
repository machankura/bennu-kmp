package to.curare.compose.identity

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.nostrPrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.NostrCredential

/**
 * An identity this device holds something for, as listed before any of them is started.
 *
 * Two stores feed this -- `seed.dat` for wallets, `nostr-credentials.dat` for bare keys
 * and bare public keys -- and the startup screen wants one list. The nostr public key
 * is on every kind because it is the one thing they have in common and the one thing a
 * duplicate check has to compare: a wallet and an imported key can be the *same* npub
 * under different ids.
 *
 * On what this holds: `SovereignWalletViewModel.availableWallets` has always carried
 * decrypted words for the view model's life, because starting the node needs them.
 * [NostrSecret] carries its key for the same reason and is no worse; decrypting at
 * activation instead would be an improvement for both kinds and belongs to both.
 */
sealed interface StoredIdentity {
    val id: WalletId
    val kind: IdentityKind
    val nostrPublicKey: HexKey

    data class Mnemonic(
        val userWallet: UserWallet,
        override val nostrPublicKey: HexKey,
    ) : StoredIdentity {
        override val id: WalletId get() = userWallet.walletId
        override val kind: IdentityKind get() = IdentityKind.Mnemonic
        override fun toString(): String = "StoredIdentity.Mnemonic(id=$id, npub=$nostrPublicKey)"
    }

    data class NostrSecret(
        override val id: WalletId,
        override val nostrPublicKey: HexKey,
        val privateKey: PrivateKey,
    ) : StoredIdentity {
        override val kind: IdentityKind get() = IdentityKind.NostrSecret
        override fun toString(): String = "StoredIdentity.NostrSecret(id=$id, npub=$nostrPublicKey, key=<redacted>)"
    }

    /**
     * A public key the device holds nothing else for. Its id is the one its secret would
     * have, so that pasting the nsec later keeps the preferences and metadata.
     */
    data class NostrPublic(
        override val id: WalletId,
        override val nostrPublicKey: HexKey,
    ) : StoredIdentity {
        override val kind: IdentityKind get() = IdentityKind.NostrPublic
    }

    companion object {
        /**
         * The nostr public key a wallet's words derive. `SeedManager.loadAndDecrypt` has
         * already built exactly this key manager to learn the node id, so this is the second
         * derivation of it, not a new cost.
         */
        fun nostrPublicKeyOf(words: List<String>): HexKey = LocalKeyManager(
            seed = MnemonicCode.toSeed(words, "").byteVector(),
            chain = NodeParamsManager.chain,
            remoteSwapInExtendedPublicKey = NodeParamsManager.remoteSwapInXpub,
        ).nostrPrivateKey().nostrPublicKeyHex()

        fun mnemonic(userWallet: UserWallet): Mnemonic =
            Mnemonic(userWallet, nostrPublicKeyOf(userWallet.words))

        fun nostrSecret(privateKey: PrivateKey): NostrSecret {
            val xOnly = privateKey.publicKey().xOnly()
            return NostrSecret(
                id = xOnly.toWalletId(),
                nostrPublicKey = xOnly.value.toHex(),
                privateKey = privateKey,
            )
        }

        fun nostrPublic(publicKeyHex: HexKey): NostrPublic = NostrPublic(
            id = publicKeyHex.toXonlyPublicKey().toWalletId(),
            nostrPublicKey = publicKeyHex,
        )

        /**
         * One map from the two stores. [credentials] is keyed by x-only public key hex, as
         * `nostr-credentials.dat` is; a secret's id is derived from its own key, so a
         * stored secret whose map key disagrees with its public key is dropped here too,
         * though `EncryptedNostrCredentials` refuses such a file before it gets this far.
         *
         * One entry per public key is the credentials file's own invariant, so there is no
         * precedence between credentials to decide. There is one between a seed and a
         * credential, for the one upgrade that has to span both files -- a recovery phrase
         * pasted over a public credential, which `IdentityWriter.writeMnemonic` performs as
         * two writes: a `Public` whose public key a seed derives is dropped, with a log line,
         * and the next write repairs the file. A *secret* for a seed's key is kept, as it
         * always was: two ids for one npub is a duplicate the writers refuse, not a state
         * the listing hides.
         */
        fun merge(
            wallets: Map<WalletId, UserWallet>,
            credentials: Map<HexKey, NostrCredential>,
        ): Map<WalletId, StoredIdentity> {
            val merged = LinkedHashMap<WalletId, StoredIdentity>()
            wallets.values.forEach { userWallet -> merged[userWallet.walletId] = mnemonic(userWallet) }
            val seedPublicKeys = merged.values.map { it.nostrPublicKey }.toSet()
            credentials.forEach { (publicKeyHex, credential) ->
                when (credential) {
                    is NostrCredential.Secret -> {
                        val stored = nostrSecret(credential.privateKey)
                        if (stored.nostrPublicKey == publicKeyHex) merged[stored.id] = stored
                    }
                    is NostrCredential.Public -> {
                        if (publicKeyHex in seedPublicKeys) {
                            log.w { "public credential for a key a seed already derives; listing the wallet only" }
                        } else {
                            val stored = runCatching { nostrPublic(publicKeyHex) }.getOrNull()
                            if (stored != null) merged[stored.id] = stored
                        }
                    }
                }
            }
            return merged
        }

        private val log = Logger.withTag("StoredIdentity")
    }
}

/** The x-only key as bitcoin-kmp types it, from the hex nostr carries it as. */
fun HexKey.toXonlyPublicKey(): XonlyPublicKey = XonlyPublicKey(ByteVector32(this))
