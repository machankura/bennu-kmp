package to.curare.compose.identity

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.security.NostrCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The merged list the startup screen reads, and the one derivation it rests on.
 *
 * The vector is NIP-06's own: twelve words, the key at `m/44'/1237'/0'/0/0`, and the
 * x-only public key. It pins that a wallet's `nostrPublicKey` is the same key an nsec
 * import of that wallet's nostr secret would produce -- which is the whole basis of the
 * duplicate check in `IdentityWriter`.
 */
class StoredIdentityJvmTest {

    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean".split(" ")
    private val nip06PrivateKey = PrivateKey(ByteVector32("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"))
    private val nip06PublicKey = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"

    private val wallet = UserWallet(
        walletId = WalletId("ab".repeat(20)),
        nodeId = "02" + "cd".repeat(32),
        words = words,
    )
    private val bareKey = PrivateKey(ByteVector32("02".repeat(32)))
    private val readOnlyKey = PrivateKey(ByteVector32("03".repeat(32))).publicKey().xOnly().value.toHex()

    @Test
    fun `a wallet's nostr key is the NIP-06 derivation of its words`() {
        assertEquals(nip06PublicKey, StoredIdentity.nostrPublicKeyOf(words))
        assertEquals(nip06PublicKey, StoredIdentity.nostrSecret(nip06PrivateKey).nostrPublicKey)
    }

    @Test
    fun `both stores land in one map, each under its own id`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = mapOf(
                bareKey.publicKey().xOnly().value.toHex() to NostrCredential.Secret(bareKey),
                readOnlyKey to NostrCredential.Public,
            ),
        )

        assertEquals(3, merged.size)
        val mnemonic = assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
        assertEquals(nip06PublicKey, mnemonic.nostrPublicKey)
        assertEquals(IdentityKind.Mnemonic, mnemonic.kind)

        val secret = assertIs<StoredIdentity.NostrSecret>(merged[bareKey.publicKey().xOnly().toWalletId()])
        assertEquals(IdentityKind.NostrSecret, secret.kind)
        assertEquals(40, secret.id.nodeIdHash.length, "same shape as a wallet's id")
        assertNotEquals(wallet.walletId, secret.id)

        val public = assertIs<StoredIdentity.NostrPublic>(merged[readOnlyKey.toXonlyPublicKey().toWalletId()])
        assertEquals(IdentityKind.NostrPublic, public.kind)
        assertEquals(readOnlyKey, public.nostrPublicKey)
    }

    /** The id a read-only identity gets is the one its secret would get, so an upgrade keeps it. */
    @Test
    fun `a public key and its secret get the same id`() {
        assertEquals(
            StoredIdentity.nostrSecret(bareKey).id,
            StoredIdentity.nostrPublic(bareKey.publicKey().xOnly().value.toHex()).id,
        )
    }

    /**
     * The same npub as a wallet, imported as a bare key, gets a *different* id -- one
     * hash is of the node key, the other of the nostr key -- so a map keyed by id holds
     * both. That is not a bug in the merge; it is why the writers dedupe by public key.
     */
    @Test
    fun `a wallet and its own nostr key as a bare secret do not collide by id`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = mapOf(nip06PublicKey to NostrCredential.Secret(nip06PrivateKey)),
        )

        assertEquals(2, merged.size)
        assertEquals(1, merged.values.map { it.nostrPublicKey }.toSet().size, "one npub, twice")
    }

    /**
     * The one precedence the merge decides. A recovery phrase pasted over a public
     * credential is written across two files -- the credential removed first, then the
     * seed -- and a crash between the two would leave neither, never both; this is the
     * rule for the "both" that is not supposed to happen, so that it lists the wallet
     * rather than one npub twice.
     */
    @Test
    fun `a public key a seed already derives is listed as the wallet only`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            credentials = mapOf(nip06PublicKey to NostrCredential.Public),
        )

        assertEquals(1, merged.size)
        assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
    }

    @Test
    fun `a key filed under a public key it does not derive is dropped`() {
        val merged = StoredIdentity.merge(
            wallets = emptyMap(),
            credentials = mapOf(nip06PublicKey to NostrCredential.Secret(bareKey)),
        )

        assertEquals(emptyMap(), merged)
    }

    @Test
    fun `the nsec form and the hex form name the same key`() {
        assertEquals(nip06PublicKey.toXonlyPublicKey().value.toHex(), nip06PublicKey)
    }
}
