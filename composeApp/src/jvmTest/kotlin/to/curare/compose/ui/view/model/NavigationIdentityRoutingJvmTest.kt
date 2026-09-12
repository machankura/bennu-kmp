package to.curare.compose.ui.view.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.UnsignedNostrEvent
import to.curare.compose.database.model.intermdiate.LocalAccount
import to.curare.compose.identity.Identity
import to.curare.compose.identity.IdentityKind
import to.curare.compose.identity.toWalletId
import to.curare.compose.repository.NostrRepository
import to.curare.compose.ui.view.state.NavigationUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * An identity with no node behind it is routed like any other.
 *
 * `NavigationViewModel.observeProfile` used to reach the active user's pubkey through
 * the wallet -- `business.walletManager.keyManager.value.nostrPublicKey()` -- and
 * answered a null `business` by navigating back to startup. For a wallet that is a
 * loop the startup screen breaks by starting the node; for an identity that will
 * never have a node it is a loop with no exit. The [Identity] type carries the key
 * itself, so the branch is gone, and this pins that it stays gone: a `NostrSecret`
 * identity with a signed, synced account on the device lands on `ProfileLoaded`.
 *
 * A jvm test rather than a common one because [Identity] derives its public key on
 * construction, which takes the secp256k1 natives, and those are on this classpath
 * (see the other tests in `jvmTest` that build a `PrivateKey`).
 *
 * The observer waits 2.1 seconds on `Dispatchers.IO` before its first read, so this
 * runs under `runBlocking` and a real-time `withTimeout`: `runTest` would advance a
 * virtual clock past the timeout while the real delay had not elapsed.
 */
class NavigationIdentityRoutingJvmTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val stranded = NavigationUIState.Loading("Introducing... Torch")

    private val privateKey = PrivateKey(ByteVector32("01".repeat(32)))

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun nostrSecretIdentity() = Identity(
        id = privateKey.publicKey().xOnly().toWalletId(),
        kind = IdentityKind.NostrSecret,
        nostrPrivateKey = privateKey,
        userPrefs = UserPrefs(prefsStore("user")),
        internalPrefs = InternalPrefs(prefsStore("internal")),
        business = null,
    )

    /** Everything `observeProfile` reads, and nothing else; every other member throws. */
    private class Device(
        private val accounts: List<LocalAccount>
    ) : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun getLocalAccounts(): List<LocalAccount> = accounts
        override suspend fun observeLocalAccount(publicKey: HexKey): Flow<LocalAccount?> =
            flowOf(accounts.firstOrNull { it.unsignedNostrEvent?.pubKey == publicKey })
    }

    private val at = Instant.fromEpochSeconds(1_700_000_000)

    private fun syncedAccount(publicKey: HexKey) = LocalAccount(
        unsignedNostrEvent = UnsignedNostrEvent(
            id = 1,
            pubKey = publicKey,
            kind = 0,
            tags = emptyArray(),
            content = "{}",
            signedAt = at,
            createdAt = at,
            updatedAt = at,
            savedAt = at,
        ),
        nostrEvent = null,
        profile = Profile(
            publicKey = publicKey,
            nostrEventId = "e".repeat(64),
            createdAt = at,
            updatedAt = at,
            savedAt = at,
        ),
        broadcastNostrEventRequest = null,
        broadcastNostrEventReceipt = null,
        synchronizeNostrEventRequests = emptyList(),
    )

    @Test
    fun `the identity's public key is the x-only key nostr uses`() {
        val identity = nostrSecretIdentity()

        // The same key quartz derives, so the notary's KeyPair and this agree on who
        // the user is. Not the 33-byte compressed form the library's own helper returns.
        assertEquals(
            KeyPair(privKey = privateKey.value.toByteArray()).pubKey.toHexKey(),
            identity.nostrPublicKey,
        )
        assertEquals(64, identity.nostrPublicKey.length)
        // Same shape as a wallet's id: forty hex characters of a hash160.
        assertEquals(40, identity.id.nodeIdHash.length)
    }

    @Test
    fun `an identity with no node behind it is routed by its account, not back to startup`() {
        val identity = nostrSecretIdentity()
        val scope = CoroutineScope(Job())
        val viewModel = NavigationViewModel(
            activeIdentityStateFlow = MutableStateFlow<Identity?>(identity),
            initialNavigationUIState = stranded,
            nostrRepository = Device(listOf(syncedAccount(identity.nostrPublicKey))),
            scope = scope,
        )

        try {
            val destination = runBlocking {
                withTimeout(15_000) { viewModel.navigationUIState.first { it != stranded } }
            }

            assertEquals(
                NavigationUIState.ProfileLoaded(publicKey = identity.nostrPublicKey),
                destination,
                "a nodeless identity was not routed by its account",
            )
        } finally {
            scope.cancel()
        }
    }
}
