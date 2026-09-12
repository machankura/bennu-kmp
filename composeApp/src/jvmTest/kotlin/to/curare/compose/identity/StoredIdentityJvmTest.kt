package to.curare.compose.identity

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.data.UserWallet
import fr.acinq.phoenix.data.WalletId
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

    @Test
    fun `a wallet's nostr key is the NIP-06 derivation of its words`() {
        assertEquals(nip06PublicKey, StoredIdentity.nostrPublicKeyOf(words))
        assertEquals(nip06PublicKey, StoredIdentity.nostrSecret(nip06PrivateKey).nostrPublicKey)
    }

    @Test
    fun `both stores land in one map, each under its own id`() {
        val merged = StoredIdentity.merge(
            wallets = mapOf(wallet.walletId to wallet),
            nostrKeys = mapOf(bareKey.publicKey().xOnly().value.toHex() to bareKey),
        )

        assertEquals(2, merged.size)
        val mnemonic = assertIs<StoredIdentity.Mnemonic>(merged[wallet.walletId])
        assertEquals(nip06PublicKey, mnemonic.nostrPublicKey)
        assertEquals(IdentityKind.Mnemonic, mnemonic.kind)

        val secret = assertIs<StoredIdentity.NostrSecret>(merged[bareKey.publicKey().xOnly().toWalletId()])
        assertEquals(IdentityKind.NostrSecret, secret.kind)
        assertEquals(40, secret.id.nodeIdHash.length, "same shape as a wallet's id")
        assertNotEquals(wallet.walletId, secret.id)
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
            nostrKeys = mapOf(nip06PublicKey to nip06PrivateKey),
        )

        assertEquals(2, merged.size)
        assertEquals(1, merged.values.map { it.nostrPublicKey }.toSet().size, "one npub, twice")
    }

    @Test
    fun `a key filed under a public key it does not derive is dropped`() {
        val merged = StoredIdentity.merge(
            wallets = emptyMap(),
            nostrKeys = mapOf(nip06PublicKey to bareKey),
        )

        assertEquals(emptyMap(), merged)
    }

    @Test
    fun `the nsec form and the hex form name the same key`() {
        assertEquals(nip06PublicKey.toXonlyPublicKey().value.toHex(), nip06PublicKey)
    }
}
