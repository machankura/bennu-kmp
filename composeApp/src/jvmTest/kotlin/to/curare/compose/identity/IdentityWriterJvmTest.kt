package to.curare.compose.identity

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.Lightning
import fr.acinq.phoenix.PhoenixGlobal
import fr.acinq.phoenix.managers.NostrKeyManager
import fr.acinq.phoenix.managers.computePreferencePath
import fr.acinq.phoenix.security.JvmKeyStore
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
 * The two writers for a bare key, against a real key store and a real directory.
 *
 * What is pinned is the duplicate rule and the inverse. A second import of the same key
 * is refused by public key, the same npub imported twice being the one thing the id
 * check cannot see; and forgetting takes the key out of the file and the identity's
 * preference files off the disk, leaving every other key where it was.
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

    private fun userPrefsFile(stored: StoredIdentity.NostrSecret) =
        computePreferencePath(phoenixGlobal.ctx, "userprefs_${stored.id.nodeIdHash}.preferences_pb")

    @Test
    fun `a key is written once and refused the second time, by public key`() = runBlocking<Unit> {
        val stored = StoredIdentity.nostrSecret(first)

        val written = assertIs<IdentityWriter.WriteNostrKeyResult.Written>(write(first))
        assertEquals(stored.id, written.id)
        assertEquals(mapOf(stored.nostrPublicKey to first), NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal))
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(stored)), "the preferences file the recovery screens read")

        assertIs<IdentityWriter.WriteNostrKeyResult.AlreadyExists>(write(first))
    }

    @Test
    fun `forgetting one key leaves the other, and takes the preferences with it`() = runBlocking<Unit> {
        write(first)
        write(second)
        val stored = StoredIdentity.nostrSecret(first)

        val result = IdentityWriter.forgetNostrKey(log, phoenixGlobal, stored.id, stored.nostrPublicKey)

        assertIs<IdentityWriter.ForgetNostrKeyResult.Forgotten>(result)
        assertEquals(
            mapOf(StoredIdentity.nostrSecret(second).nostrPublicKey to second),
            NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal),
        )
        assertFalse(FileSystem.SYSTEM.exists(userPrefsFile(stored)))
        assertTrue(FileSystem.SYSTEM.exists(userPrefsFile(StoredIdentity.nostrSecret(second))))
    }

    /** A wallet's nostr key is in the seed, not here; removing a seed is a wallet question. */
    @Test
    fun `a key that is not in the file is not forgotten`() = runBlocking<Unit> {
        write(first)
        val other = StoredIdentity.nostrSecret(second)

        assertIs<IdentityWriter.ForgetNostrKeyResult.NotABareKey>(
            IdentityWriter.forgetNostrKey(log, phoenixGlobal, other.id, other.nostrPublicKey)
        )
        assertEquals(1, NostrKeyManager.loadAndDecryptOrNull(phoenixGlobal)?.size)
    }
}
