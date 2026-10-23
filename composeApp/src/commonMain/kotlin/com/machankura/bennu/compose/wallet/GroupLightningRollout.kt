package com.machankura.bennu.compose.wallet

import fr.acinq.bitcoin.Chain

/**
 * The gates the group Lightning wallet ships behind -- `docs/group-lightning-wallet.md`, Phase 15 --
 * in one place, each with the condition that lifts it.
 *
 * As with [GroupWalletRollout], these decide what this device offers, never what it accepts: a device
 * with a gate closed still verifies and co-signs for a wallet another member opened.
 */
object GroupLightningRollout {
    /**
     * Whether the group's screen offers a Lightning wallet, and the test-network on-chain wallet it closes
     * to. Lifts when Phases 0 to 12 are in, the JVM suite passes and a regtest end-to-end run passes.
     */
    const val ENABLED: Boolean = false

    /**
     * The only network a Lightning wallet may be opened on. Iceberg says not to put money behind it, so
     * mainnet is refused in code rather than hidden; this lifts only when the scheme and the DKG have
     * been reviewed outside the project and a testnet3 group has run for a month without a refused label
     * or a lost channel.
     */
    val NETWORK: Chain = Chain.Testnet3

    fun allows(chain: Chain): Boolean = chain == NETWORK
}
