package to.curare.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlinx.coroutines.runBlocking
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.builder.getRoomDatabase
import to.curare.compose.database.model.NostrEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * A gift wrap addressed to a read-only identity is kept and not opened.
 *
 * `storeNostrEvent` is `@Transaction` and indexes inside it, and a wrap addressed to the
 * active key that cannot be unsealed throws -- so before the guard in `indexNostrEvent`
 * the throw took the event with it, and looked in the logs like a decryption failure.
 * Worse, `decryptGiftWrapSeal` builds `KeyPair(privKey = keyPair.privKey)`, and in
 * quartz a `KeyPair` given neither key generates a fresh one: the unseal would have run
 * with a key nobody has. A read-only identity holds no key by construction, and the
 * wrap is what the branch for someone else's mail already does with it: stored, so the
 * key that opens it can be signed in later.
 */
class ReadOnlyGiftWrapDaoJvmTest {

    private val db: CurareDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<CurareDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val relay = "wss://relay.example"

    /** The user, as the sync pump sees them when the device holds only their public key. */
    private val us = KeyPair()
    private val readOnlyUs = KeyPair(pubKey = us.pubKey)
    private val peer = KeyPair()

    private fun wrapTo(recipient: KeyPair): NostrEvent {
        val signer = NostrSignerSync(peer)
        val seal = signer.signNormal<SealedRumorEvent>(
            createdAt = 1_700_000_000,
            kind = SealedRumorEvent.KIND,
            tags = emptyArray(),
            content = signer.nip44Encrypt(
                plaintext = """{"kind":14,"content":"dumela"}""",
                toPublicKey = recipient.pubKey.toHexKey(),
            ),
        )
        val giftWrap = GiftWrapEvent.create(
            event = seal,
            recipientPubKey = recipient.pubKey.toHexKey(),
            createdAt = 1_700_000_100,
        )
        return NostrEvent(
            id = giftWrap.id,
            pubKey = giftWrap.pubKey,
            kind = giftWrap.kind,
            tags = giftWrap.tags,
            content = giftWrap.content,
            sig = giftWrap.sig,
            createdAt = Instant.fromEpochSeconds(giftWrap.createdAt),
        )
    }

    @Test
    fun `a wrap addressed to a read-only identity is stored, and its seal is not`() = runBlocking {
        val wrap = wrapTo(us)

        db.nostrDao().storeNostrEvent(
            nostrEvent = wrap,
            relayURL = relay,
            synchronizationRelayURLs = listOf(relay),
            level = 0,
            activeKeyPair = readOnlyUs,
        )

        assertNotNull(db.nostrEventDao().getNostrEventById(wrap.id), "the event itself must survive the failed unseal")
        assertEquals(listOf(wrap.id), db.giftWrapMessageDao().getAllGiftWrapMessages().map { it.id })
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "nothing was opened")
    }

    /** The same wrap, once the key is here: the seal comes out. The guard is only about the key's absence. */
    @Test
    fun `the same wrap opens once the identity holds its key`() = runBlocking {
        val wrap = wrapTo(us)

        db.nostrDao().storeNostrEvent(
            nostrEvent = wrap,
            relayURL = relay,
            synchronizationRelayURLs = listOf(relay),
            level = 0,
            activeKeyPair = us,
        )

        assertEquals(1, db.giftWrapSealDao().getAllGiftWrapSeals().size)
    }
}
