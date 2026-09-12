package to.curare.compose.identity

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.byteVector
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs

/**
 * Where an identity's key came from, which decides what else it can have.
 *
 * The distinction is not cosmetic: the nostr key is a BIP32 leaf of the seed at
 * `m/44'/1237'/0'/0/0`, and that derivation runs one way. Twelve words yield the
 * key and a wallet; a bare key yields nothing further, so an identity made from
 * one can never grow a node behind it. See docs/nsec-sign-in.md.
 */
enum class IdentityKind {
    /** Twelve words. The nostr key is derived from them, and so is a wallet. */
    Mnemonic,

    /** A bare nostr secret. Nothing else can be derived from it. */
    NostrSecret,
}

/**
 * The signing identity the app is running as.
 *
 * This is what every consumer of "the active user" reads, in place of reaching
 * through the wallet -- `business.walletManager.keyManager.value.nostrPrivateKey()`
 * -- for a key that only some identities derive that way. Everything downstream of
 * the key is already independent of the seed: the notary and the sync pumps build
 * a quartz `KeyPair` from its bytes, the ChillDKG host key is a hash of it, and the
 * Marmot bundle password is `hash160` of it. None of them can tell where it came
 * from, and after this type none of them need to.
 *
 * @param id keys the per-identity preference files and the wallet metadata. For a
 *   [IdentityKind.Mnemonic] identity it is the hash160 of the node id, as it always
 *   was; for a [IdentityKind.NostrSecret] identity it is the hash160 of the x-only
 *   public key ([toWalletId]). Same shape, so nothing downstream can tell the kinds
 *   apart by the id -- the kind is here, where it can be asked.
 * @param business the running node. Non-null only for [IdentityKind.Mnemonic].
 */
data class Identity(
    val id: WalletId,
    val kind: IdentityKind,
    val nostrPrivateKey: PrivateKey,
    val userPrefs: UserPrefs,
    val internalPrefs: InternalPrefs,
    val business: PhoenixBusiness?,
) {
    /**
     * X-only, hex -- the form every nostr call site wants. Computed once.
     *
     * Not `publicKey().toHex()`: that is the 33-byte compressed encoding, sixty-six
     * hex characters, and it is what the library's own `nostrPublicKey()` returns.
     * Nostr keys are the 32-byte x coordinate.
     */
    val nostrPublicKey: HexKey = nostrPrivateKey.publicKey().xOnly().value.toHex()

    /** The key must never reach a log. `data class` would print it. */
    override fun toString(): String = "Identity(id=$id, kind=$kind, key=<redacted>)"
}

/**
 * The [WalletId] of an identity that has no node id to hash.
 *
 * `WalletId(nodeId: PublicKey)` is the hash160 of the compressed node key; this is
 * the hash160 of the x-only nostr key. The two are over different keys -- the node
 * key sits at `m/50'/0'`, the nostr key at `m/44'/1237'/...` -- so a mnemonic wallet
 * and its own nostr identity would never share an id even if both were registered.
 * Which is exactly why a duplicate has to be caught by public key instead.
 */
fun XonlyPublicKey.toWalletId(): WalletId =
    WalletId(Crypto.hash160(value).byteVector().toHex())
