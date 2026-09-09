package com.it.curated.compose.repository

import com.it.curated.compose.cryptography.signOrThrow
import com.it.curated.compose.network.asClientTag
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
import fr.acinq.phoenix.managers.WalletManager
import fr.acinq.phoenix.managers.nostrPrivateKey
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
    private val walletManager: WalletManager,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _effects = Channel<NotarySideEffect>()
    val effects = _effects.receiveAsFlow()
    private fun setEffect(effect: NotarySideEffect) = scope.launch { _effects.send(effect) }

    private val signMutex = Mutex()

    private val _responses = Channel<SignResult>()
    private fun setResponse(response: SignResult) = scope.launch { _responses.send(response) }

    suspend fun signNostrEvent(unsignedNostrEvent: com.it.curated.compose.database.model.UnsignedNostrEvent): SignResult {
        val result = try {
            signNostrEvent(publicKey = unsignedNostrEvent.pubKey, event = unsignedNostrEvent)
        } catch (error: com.it.curated.compose.exceptions.SignatureException) {
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

    fun verifySignature(nostrEvent: com.it.curated.compose.database.model.NostrEvent): Boolean {
        throw NotImplementedError()
    }

    fun onSuccess(nostrEvent: com.it.curated.compose.database.model.NostrEvent) {
        setResponse(SignResult.Signed(nostrEvent))
    }

    fun onFailure() {
        setResponse(SignResult.Rejected(com.it.curated.compose.exceptions.SigningRejectedException()))
    }

    private fun findNsecOrThrow(activeUserPublicKey: String): String =
        runCatching {
            walletManager.keyManager.value?.nostrPrivateKey()?.value?.toByteArray()?.toNsec()
        }.getOrNull() ?: throw com.it.curated.compose.exceptions.SigningKeyNotFoundException()

    private fun signNostrEvent(publicKey: String, event: com.it.curated.compose.database.model.UnsignedNostrEvent): com.it.curated.compose.database.model.NostrEvent {
        val isExternalSignerLogin = runCatching {
//            credentialsStore.isExternalSignerCredential(npub = publicKey.hexToNpubHrp())
            false
        }.getOrDefault(false)

        if (isExternalSignerLogin) {
            throw NotImplementedError()
        }

        return event.signOrThrow(nsec = findNsecOrThrow(publicKey))
    }

    suspend fun signRelayListMetadata(userId: String, relays: List<com.it.curated.compose.network.dto.RelayDTO>): SignResult {
        return signNostrEvent(
            unsignedNostrEvent = com.it.curated.compose.database.model.UnsignedNostrEvent(
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
                    .toTypedArray() + listOf(com.it.curated.compose.network.UserAgent.CLIENT_NAME.asClientTag()),
            ),
        )
    }

    sealed class NotarySideEffect {
        data class RequestSignature(val unsignedEvent: com.it.curated.compose.database.model.UnsignedNostrEvent) : NotarySideEffect()
    }

    sealed class SignResult {
        data class Signed(val event: com.it.curated.compose.database.model.NostrEvent) : SignResult()
        data class Rejected(val error: com.it.curated.compose.exceptions.SignatureException) : SignResult()

        fun unwrapOrThrow(onFailure: ((com.it.curated.compose.exceptions.SignatureException) -> Unit)? = null): com.it.curated.compose.database.model.NostrEvent =
            when (this) {
                is Rejected -> {
                    onFailure?.invoke(this.error)
                    throw this.error
                }

                is Signed -> {
                    this.event
                }
            }

        fun getOrNull(onFailure: ((com.it.curated.compose.exceptions.SignatureException) -> Unit)? = null): com.it.curated.compose.database.model.NostrEvent? =
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