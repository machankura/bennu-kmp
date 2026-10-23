package com.machankura.bennu.compose.wallet

import co.touchlab.kermit.Logger
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ElectrumServers
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.logging.LoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * The Electrum connection each network's group wallets watch through, for one identity.
 *
 * The identity's own node has one, for the network the node runs on, and every wallet on that network
 * shares it as before. A group may keep a wallet on another network as well -- testnet3, while its
 * Lightning wallet is tried there (`docs/group-lightning-wallet.md`, Phase 4) -- and that network gets a
 * connection of its own, made the first time a wallet on it asks and kept for the identity's life, for
 * the reason [GroupOnchainWallets] keeps its wallets: a subscription is cheap and the first answer slow.
 */
class GroupChainClients(
    private val nodeChain: Chain,
    private val nodeClient: IElectrumClient,
    private val connect: suspend (Chain) -> Connection,
) {
    /** A connection this object made, and how to close it. */
    class Connection(val client: IElectrumClient, val close: () -> Unit)

    private val mutex = Mutex()
    private val others = mutableMapOf<Chain, Connection>()

    suspend fun clientFor(chain: Chain): IElectrumClient =
        if (chain == nodeChain) nodeClient else mutex.withLock { others.getOrPut(chain) { connect(chain) }.client }

    suspend fun stop() = mutex.withLock {
        others.values.forEach { it.close() }
        others.clear()
    }

    companion object {
        /** Every network on one client: for a test, whose fake client answers for whatever it is asked. */
        fun single(client: IElectrumClient): GroupChainClients =
            GroupChainClients(Chain.Mainnet, client) { Connection(client) {} }

        /**
         * The identity's node for its own network, and a standalone [ElectrumClient] on a random public
         * server for any other that lightning-kmp lists servers for (mainnet and testnet3), reconnecting
         * whenever it drops.
         */
        fun forNode(nodeChain: Chain, nodeClient: IElectrumClient, scope: CoroutineScope, loggerFactory: LoggerFactory): GroupChainClients =
            GroupChainClients(nodeChain, nodeClient) { chain -> standalone(chain, scope, loggerFactory) }

        private fun standalone(chain: Chain, scope: CoroutineScope, loggerFactory: LoggerFactory): Connection {
            val logger = Logger.withTag("GroupChainClients")
            val client = ElectrumClient(scope, loggerFactory, pingInterval = 30.seconds, rpcTimeout = 10.seconds)
            val socketBuilder = TcpSocket.Builder()
            val job: Job = scope.launch {
                var backoff = 1.seconds
                while (isActive) {
                    if (client.connectionStatus.value is ElectrumConnectionStatus.Closed) {
                        val server = ElectrumServers.pickElectrumServer(chain)
                        val connected = try {
                            client.connect(server, socketBuilder)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logger.w("Could not reach ${server.host} for $chain", e)
                            false
                        }
                        backoff = if (connected) 1.seconds else (backoff * 2).coerceAtMost(60.seconds)
                    }
                    delay(backoff)
                }
            }
            return Connection(client) {
                job.cancel()
                client.stop()
            }
        }
    }
}
