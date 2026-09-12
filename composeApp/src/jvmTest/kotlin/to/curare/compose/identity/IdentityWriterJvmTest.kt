package to.curare.compose.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.managers.NostrCredentialManager
import fr.acinq.phoenix.managers.computePreferencePath
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.security.JvmKeyStore
import fr.acinq.phoenix.security.NostrCredential
import fr.acinq.phoenix.utils.PlatformContext
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.SYSTEM
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The writers for a bare key and a bare public key, against a real key store and a
 * real directory.
 *
 * What is pinned is the duplicate rule, its one exception, and the inverse. A second
 * import of the same key is refused by public key, the same npub imported twice being
 * the one thing the id check cannot see; a public key already here is *not* a duplicate
 * of the secret that signs as it, and the entry becomes a secret one under the id it
 * had; and forgetting takes the entry, whichever kind, out of the file and the identity's
 * preference files off the disk, leaving every other entry where it was.
 */
class IdentityWriterJvmTest {

    private lateinit var storeDir: File
    private lateinit var appDir: File
    private lateinit var phoenixGlobal: PhoenixGlobal
    private lateinit var globalPrefs: GlobalPrefs

    private val log = Logger.withTag("IdentityWriterJvmTest")

    // Fresh keys per test, not fixed ones: DataStoreManager caches each id's preferences
    // in a companion object for the life of the process, so a fixed key would be served
    // the UserPrefs an earlier test created in an earlier temp directory.
    private val first = PrivateKey(Lightning.randomBytes(32))
    private val second = PrivateKey(Lightning.randomBytes(32))

    @BeforeTest
    fun setUp() {
        storeDir = Files.createTempDirectory("curare-identity-writer-store").toFile()
        appDir = Files.createTempDirectory("curare-identity-writer-app").toFile()
        JvmKeyStore.lock()
        JvmKeyStore.unlock("correct horse battery staple".toCharArray(), storeDir)
        phoenixGlobal = PhoenixGlobal(PlatformContext(applicationDir = appDir))
        globalPrefs = GlobalPrefs(
            PreferenceDataStoreFactory.createWithPath {
                computePreferencePath(phoenixGlobal.ctx, "globalprefs.preferences_pb")
            }
        )
    }

    @AfterTest
    fun tearDown() {
        JvmKeyStore.lock()
        storeDir.deleteRecursively()
        appDir.deleteRecursively()
    }

    private suspend fun write(key: PrivateKey) = IdentityWriter.writeNostrKey(
        log = log,
        phoenixGlobal = phoenixGlobal,
        globalPrefs = globalPrefs,
        privateKey = key,
        isTorEnabled = false,
        customElectrumServer = null,
    )

    private suspend fun writePublic(key: PrivateKey) = IdentityWriter.writeNostrPublicKey(
        log = log,
        phoenixGlobal = phoenixGlobal,
        globalPrefs = globalPrefs,
        nostrPublicKey = key.nostrPublicKeyHex(),
        isTorEnabled = false,
        customElectrumServer = null,
    )

    private fun credentials() = NostrCredentialManager.loadAndDecryptOrNull(phoenixGlobal)

    private fun userPrefsFile(stored: StoredIdentity) =
        computePreferencePath(phoenixGlobal.ctx, "userprefs_${stored.id.nodeIdHash}.preferences_pb")

    @Test
    fun `a key is written once and refused the second time, by public key`() = runBlocking<Unit> {
        val stored = StoredIdentity.nostrSecret(first)

        val written = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(write(first))
        assertEquals(stored.id, written.id)
        assertEquals(mapOf(stored.nostrPublicKey to NostrCredential.Secret(first)), credentials())
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(stored)), "the preferences file the recovery screens read")

        assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(write(first))
    }

    @Test
    fun `a public key is written once and refused the second time`() = runBlocking<Unit> {
        val stored = StoredIdentity.nostrPublic(first.nostrPublicKeyHex())

        val written = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(writePublic(first))
        assertEquals(stored.id, written.id)
        assertEquals(mapOf(stored.nostrPublicKey to NostrCredential.Public), credentials())
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(stored)))

        assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(writePublic(first))
    }

    /**
     * The one exception to the duplicate rule, and the reason the two kinds share a
     * file: the device does not hold this secret, so refusing it would be false. One
     * write, same id, and the preferences the read-only identity had are the ones the
     * signing identity keeps.
     */
    @Test
    fun `the nsec of a key held read-only is accepted, under the same id, and the entry becomes a secret`() = runBlocking<Unit> {
        val readOnly = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(writePublic(first))
        writePublic(second)

        val upgraded = assertIs<IdentityWriter.WriteNostrCredentialResult.Written>(write(first))

        assertEquals(readOnly.id, upgraded.id)
        assertEquals(
            mapOf(
                first.nostrPublicKeyHex() to NostrCredential.Secret(first),
                second.nostrPublicKeyHex() to NostrCredential.Public,
            ),
            credentials(),
        )
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(StoredIdentity.nostrSecret(first))))
    }

    /** The other direction is a duplicate: the device already holds more than an npub for the key. */
    @Test
    fun `the npub of a key held as a secret is refused`() = runBlocking<Unit> {
        write(first)

        assertIs<IdentityWriter.WriteNostrCredentialResult.AlreadyExists>(writePublic(first))
        assertEquals(mapOf(first.nostrPublicKeyHex() to NostrCredential.Secret(first)), credentials())
    }

    @Test
    fun `forgetting one entry leaves the others, whichever kind, and takes the preferences with it`() = runBlocking<Unit> {
        write(first)
        writePublic(second)
        val stored = StoredIdentity.nostrSecret(first)

        val result = IdentityWriter.forgetNostrCredential(log, phoenixGlobal, stored.id, stored.nostrPublicKey)

        assertIs<IdentityWriter.ForgetNostrCredentialResult.Forgotten>(result)
        assertEquals(mapOf(second.nostrPublicKeyHex() to NostrCredential.Public), credentials())
        assertFalse(FileSystem.SYSTEM.exists(userPrefsFile(stored)))
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(StoredIdentity.nostrPublic(second.nostrPublicKeyHex()))))

        val readOnly = StoredIdentity.nostrPublic(second.nostrPublicKeyHex())
        assertIs<IdentityWriter.ForgetNostrCredentialResult.Forgotten>(
            IdentityWriter.forgetNostrCredential(log, phoenixGlobal, readOnly.id, readOnly.nostrPublicKey)
        )
        assertEquals(emptyMap(), credentials())
        assertFalse(FileSystem.SYSTEM.exists(userPrefsFile(readOnly)))
    }

    /** A wallet's nostr key is in the seed, not here; removing a seed is a wallet question. */
    @Test
    fun `a key that is not in the file is not forgotten`() = runBlocking<Unit> {
        write(first)
        val other = StoredIdentity.nostrSecret(second)

        assertIs<IdentityWriter.ForgetNostrCredentialResult.NotACredential>(
            IdentityWriter.forgetNostrCredential(log, phoenixGlobal, other.id, other.nostrPublicKey)
        )
        assertEquals(1, credentials()?.size)
    }
}
