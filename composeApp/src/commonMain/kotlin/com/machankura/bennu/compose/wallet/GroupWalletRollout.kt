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
     * The most one spend proposed from this device may send on mainnet -- and,
     * since a member can send an amount with change on every network, the
     * bound on what a builder bug in that change could put at risk. Lifts
     * when a release has run real spends end to end with no session that failed
     * to aggregate and no transaction that failed to confirm. Test networks have
     * no cap: nothing on them is worth one.
     */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    fun capFor(chain: Chain): Satoshi? = MAINNET_CAP.takeIf { chain.isMainnet() }

    /**
     * Whether a subgroup is offered a guarded wallet on mainnet --
     * `docs/group-wallet-guardians.md`. Test networks were offered one from the
     * start. On: lifted by decision, ahead of the signet run its own condition
     * named -- a guardian spend made by a real three-level hierarchy. What it
     * still leaves open is the mixed-version risk: a member on a build that
     * predates guarded statements sees no wallet, and can open a plain one whose
     * addresses the rest do not watch. Every guardian spend on mainnet is held to
     * [MAINNET_CAP] like any other.
     */
    const val GUARDIANS_ON_MAINNET: Boolean = true

    fun offersGuardiansOn(chain: Chain): Boolean = GUARDIANS_ON_MAINNET || !chain.isMainnet()
}
