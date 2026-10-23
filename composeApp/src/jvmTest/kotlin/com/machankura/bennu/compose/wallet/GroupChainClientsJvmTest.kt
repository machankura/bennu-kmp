package com.machankura.bennu.compose.wallet

import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * One Electrum connection per network: the node's own is used as it is, any other is made once, on
 * first use, and closed with the identity.
 */
class GroupChainClientsJvmTest {
    @Test
    fun `the node's network uses the node's client and connects nothing`() = runBlocking<Unit> {
        val node = FakeElectrum()
        val made = mutableListOf<Chain>()
        val clients = GroupChainClients(Chain.Mainnet, node) { chain -> made += chain; GroupChainClients.Connection(FakeElectrum()) {} }
        assertSame(node, clients.clientFor(Chain.Mainnet))
        assertEquals(emptyList(), made)
    }

    @Test
    fun `another network gets one connection of its own, reused, and closed on stop`() = runBlocking<Unit> {
        val node = FakeElectrum()
        val made = mutableListOf<Chain>()
        var closed = 0
        val clients = GroupChainClients(Chain.Mainnet, node) { chain -> made += chain; GroupChainClients.Connection(FakeElectrum()) { closed++ } }
        val first = clients.clientFor(Chain.Testnet3)
        assertSame(first, clients.clientFor(Chain.Testnet3))
        assertEquals(listOf<Chain>(Chain.Testnet3), made)
        clients.stop()
        assertEquals(1, closed)
        // A wallet asked for after a stop -- a profile switched back -- connects again.
        clients.clientFor(Chain.Testnet3)
        assertEquals(listOf<Chain>(Chain.Testnet3, Chain.Testnet3), made)
    }
}
