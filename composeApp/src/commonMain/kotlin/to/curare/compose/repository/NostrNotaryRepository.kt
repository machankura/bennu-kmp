package to.curare.compose.repository

import to.curare.compose.cryptography.signOrThrow
import to.curare.compose.network.asClientTag
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
import kotlinx.coroutines.flow.StateFlow
import to.curare.compose.identity.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * As seen in Primal
 *
 * https://github.com/PrimalHQ/primal-android-app/blob/86e8d29df7f780e8d19ccff2362d7dc6398da634/app/src/main/kotlin/net/primal/android/nostr/notary/NostrNotary.kt
 *
 * TODO: Make this a Singleton
 */
class NostrNotaryRepository(
    private val nostrRepository: NostrRepository,
    private val activeIdentityStateFlow: StateFlow<Identity?>,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _effects = Channel<NotarySideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: NotarySideEffect) = scope.launch { _effects.send(effect) }

    private val signMutex = Mutex()

    private val _responses = Channel<SignResult>()
    private fun setResponse(response: SignResult) = scope.launch { _responses.send(response) }

    suspend fun signNostrEvent(unsignedNostrEvent: to.curare.compose.database.model.UnsignedNostrEvent): SignResult {
        val result = try {
            signNostrEvent(publicKey = unsignedNostrEvent.pubKey, event = unsignedNostrEvent)
        } catch (error: to.curare.compose.exceptions.SignatureException) {
            return SignResult.Rejected(error)
        }

        return if (result != null) {
            SignResult.Signed(result)
        } else {
            signMutex.withLock {
                setEffect(NotarySideEffect.RequestSignature(unsignedNostrEvent))
                _responses.receive()
            }
        }
    }

    fun verifySignature(nostrEvent: to.curare.compose.database.model.NostrEvent): Boolean {
        throw NotImplementedError()
    }

    fun onSuccess(nostrEvent: to.curare.compose.database.model.NostrEvent) {
        setResponse(SignResult.Signed(nostrEvent))
    }

    fun onFailure() {
        setResponse(SignResult.Rejected(to.curare.compose.exceptions.SigningRejectedException()))
    }

    private fun findNsecOrThrow(activeUserPublicKey: String): String =
        runCatching {
            activeIdentityStateFlow.value?.nostrPrivateKey?.value?.toByteArray()?.toNsec()
        }.getOrNull() ?: throw to.curare.compose.exceptions.SigningKeyNotFoundException()

    private fun signNostrEvent(publicKey: String, event: to.curare.compose.database.model.UnsignedNostrEvent): to.curare.compose.database.model.NostrEvent {
        val isExternalSignerLogin = runCatching {
//            credentialsStore.isExternalSignerCredential(npub = publicKey.hexToNpubHrp())
            false
        }.getOrDefault(false)

        if (isExternalSignerLogin) {
            throw NotImplementedError()
        }

        return event.signOrThrow(nsec = findNsecOrThrow(publicKey))
    }

    suspend fun signRelayListMetadata(userId: String, relays: List<to.curare.compose.network.dto.RelayDTO>): SignResult {
        return signNostrEvent(
            unsignedNostrEvent = to.curare.compose.database.model.UnsignedNostrEvent(
                pubKey = userId,
                content = "",
                kind = RelayFeedsListEvent.KIND,
                tags = relays.map {
                    arrayOf(
                        "r",
                        it.url,
                        when {
                            it.read -> "read"
                            it.write -> "write"
                            else -> ""
                        }
                    )
                }
                    .toTypedArray() + listOf(to.curare.compose.network.UserAgent.CLIENT_NAME.asClientTag()),
            ),
        )
    }

    sealed class NotarySideEffect {
        data class RequestSignature(val unsignedEvent: to.curare.compose.database.model.UnsignedNostrEvent) : NotarySideEffect()
    }

    sealed class SignResult {
        data class Signed(val event: to.curare.compose.database.model.NostrEvent) : SignResult()
        data class Rejected(val error: to.curare.compose.exceptions.SignatureException) : SignResult()

        fun unwrapOrThrow(onFailure: ((to.curare.compose.exceptions.SignatureException) -> Unit)? = null): to.curare.compose.database.model.NostrEvent =
            when (this) {
                is Rejected -> {
                    onFailure?.invoke(this.error)
                    throw this.error
                }

                is Signed -> {
                    this.event
                }
            }

        fun getOrNull(onFailure: ((to.curare.compose.exceptions.SignatureException) -> Unit)? = null): to.curare.compose.database.model.NostrEvent? =
            when (this) {
                is Rejected -> {
                    onFailure?.invoke(this.error)
                    null
                }

                is Signed -> {
                    this.event
                }
            }

        fun getOrThrow(error: Throwable) =
            when (this) {
                is Rejected -> throw error
                is Signed -> this.event
            }
    }
}