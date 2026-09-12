package to.curare.compose.identity

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

/**
 * An identity this device holds the secret for, as listed before any of them is started.
 *
 * Two stores feed this -- `seed.dat` for wallets, `nostr-keys.dat` for bare keys -- and
 * the startup screen wants one list. The nostr public key is on both kinds because it
 * is the one thing they have in common and the one thing a duplicate check has to
 * compare: a wallet and an imported key can be the *same* npub under different ids.
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

        /**
         * One map from the two stores. [nostrKeys] is keyed by x-only public key hex, as
         * `nostr-keys.dat` is; the id of each is derived from that key, so a stored key
         * whose map key disagrees with its own public key is dropped here too, though
         * `EncryptedNostrKeys` refuses such a file before it gets this far.
         */
        fun merge(
            wallets: Map<WalletId, UserWallet>,
            nostrKeys: Map<HexKey, PrivateKey>,
        ): Map<WalletId, StoredIdentity> {
            val merged = LinkedHashMap<WalletId, StoredIdentity>()
            wallets.values.forEach { userWallet -> merged[userWallet.walletId] = mnemonic(userWallet) }
            nostrKeys.forEach { (publicKeyHex, privateKey) ->
                val stored = nostrSecret(privateKey)
                if (stored.nostrPublicKey == publicKeyHex) merged[stored.id] = stored
            }
            return merged
        }
    }
}

/** The x-only key as bitcoin-kmp types it, from the hex nostr carries it as. */
fun HexKey.toXonlyPublicKey(): XonlyPublicKey = XonlyPublicKey(ByteVector32(this))
