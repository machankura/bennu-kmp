package com.machankura.bennu.compose.wallet

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/**
 * The gates the group wallet ships behind -- `docs/group-chain-wallet.md`, Phase
 * 10 -- in one place, each with the condition that lifts it.
 *
 * Entry points only. A device with a gate closed still accepts, shows and signs
 * a spend another member proposes, through every check `GroupSpend` makes: a
 * gate decides what this device offers, never what a quorum may decide.
 */
object GroupWalletRollout {
    /**
     * Whether the group's screen offers a wallet at all. Lifted when Phases 1-9
     * were in and the JVM suite passed, which they are and it did.
     */
    const val ENABLED: Boolean = true

    /**
     * Whether a member can send a chosen amount, with change, or only everything
     * to one address. Send-all only until send-an-amount has had a release with
     * nobody reporting a mislabelled change output.
     */
    const val SEND_AN_AMOUNT: Boolean = false

    /**
     * The most one spend proposed from this device may send on mainnet. Lifts
     * when a release has run real spends end to end with no session that failed
     * to aggregate and no transaction that failed to confirm. Test networks have
     * no cap: nothing on them is worth one.
     */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    fun capFor(chain: Chain): Satoshi? = MAINNET_CAP.takeIf { chain.isMainnet() }
}
