package com.machankura.bennu.compose.wallet

import co.touchlab.kermit.Logger
import com.machankura.bennu.compose.identity.Identity
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.phoenix.managers.NodeParamsManager
import fr.acinq.phoenix.managers.global.NetworkMonitor
import fr.acinq.phoenix.data.WalletId

/**
 * The Electrum client the active identity watches the chain on.
 *
 * A profile with a node watches on the node's client; one with no node -- an nsec
 * or an npub -- gets a [StandaloneElectrum] of its own. Never both: a second
 * connection from a profile that has one would tell a second server the same
 * addresses, and give the wallets a chain that can disagree with the node's (D1 of
 * `docs/standalone-electrum.md`). The test is the node client, not the kind, as
 * `switchToIdentity` tests `business`.
 *
 * One per app, held by the nav host. A change of identity stops the last
 * standalone client *before* the next is made, so a switch between two nsec
 * profiles never has two connections open; a stopped client cannot be restarted,
 * so the next is always new.
 */
class IdentityElectrum(
    private val standaloneFor: (Identity) -> StandaloneElectrum,
) {
    private val logger = Logger.withTag("IdentityElectrum")

    private var key: Pair<WalletId, IElectrumClient?>? = null
    private var client: IElectrumClient? = null
    private var standalone: StandaloneElectrum? = null

    /**
     * The client for [identity], made the first time it is asked for and kept while
     * it stays the same identity on the same node. Null only with no identity.
     */
    fun clientFor(identity: Identity?, nodeClient: IElectrumClient? = identity?.business?.electrumClient): IElectrumClient? {
        val next = identity?.let { it.id to nodeClient }
        if (next == key) return client

        stop()
        key = next
        client = when {
            identity == null -> null
            nodeClient != null -> nodeClient
            else -> {
                logger.i { "starting an Electrum client for ${identity.id}, which has no node" }
                standaloneFor(identity).also { it.start() }.also { standalone = it }.client
            }
        }
        return client
    }

    /** Stops the standalone client, if the identity has one. The node's is the node's to stop. */
    fun stop() {
        standalone?.stop()
        standalone = null
        client = null
        key = null
    }

    companion object {
        /** The real thing: the identity's own Electrum preferences, the device's network, the node's network. */
        fun standalone(networkMonitor: NetworkMonitor, loggerFactory: LoggerFactory): (Identity) -> StandaloneElectrum = { identity ->
            // Idempotent on every platform, and otherwise only the node's daemon calls it.
            networkMonitor.start()
            StandaloneElectrum(
                chain = NodeParamsManager.chain,
                networkState = networkMonitor.networkState,
                isTorEnabled = identity.userPrefs.getIsTorEnabled,
                customServer = identity.userPrefs.getElectrumServer,
                link = ElectrumClientLink(loggerFactory),
            )
        }
    }
}
