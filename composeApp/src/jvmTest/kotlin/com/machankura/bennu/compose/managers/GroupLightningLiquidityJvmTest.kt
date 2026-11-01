package com.machankura.bennu.compose.managers

import com.machankura.bennu.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.bennu.compose.text.ProposedEvent
import com.machankura.bennu.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The group deciding to buy inbound liquidity (`docs/group-lightning-liquidity.md`, Phase 4): a
 * `buy-liquidity` authorisation reads back as what it was, an older build reads it as nothing, the
 * proposal says what a signer weighs, and a purchase past the mainnet cap is refused before anyone is asked.
 */
abstract class GroupLightningLiquidityCases(chain: Chain) {
    private val fixture = GroupLightningFixture(chain = chain)
    private val lsp = "02" + KeyPair().pubKey.toHexKey()
    private val channelId = "ab".repeat(32)

    @AfterTest
    fun close() = fixture.close()

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupLightningWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all)
        fixture.signSession(all, all[0], GroupLightningWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, lsp).id)
        fixture.signSession(all, all[1], GroupLightningOperatorManager.nominateSelf(all[1].db, all[1].room, all[1].publicKey, fixture.chain).id)
        return all to GroupLightningWalletManager.walletFor(all[3].db, fixture.roomId, fixture.chain)!!
    }

    @Test
    fun `a purchase reads back as what it was, and nothing else reads it`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, capacitySat = 25_000, amountSat = 500_000, maxFeeSat = 7_500).getOrThrow()
        assertEquals(500_000, buy.amountSat)
        assertEquals(7_500, buy.maxFeeSat)
        assertTrue(buy.expiresAt > Clock.System.now().epochSeconds)
        val tags = GroupLightningAuthorisationEvent.template(buy).tags
        assertEquals(buy, GroupLightningAuthorisationEvent.readBuyLiquidity(tags))
        // An older build, which knows only payments and channel actions, reads it as no authorisation at all.
        assertNull(GroupLightningAuthorisationEvent.read(tags))
        assertNull(GroupLightningAuthorisationEvent.readChannelAction(tags))
        // And the purchase reader reads nothing else.
        val close = GroupLightningAuthorisationEvent.ChannelAction(GroupLightningAuthorisationEvent.CLOSE, fixture.chain, 0, channelId, buy.expiresAt)
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(GroupLightningAuthorisationEvent.template(close).tags))

        fun with(name: String, value: String) = tags.map { if (it[0] == name) arrayOf(name, value) else it }.toTypedArray()
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("channel", "cd".repeat(32))), "a channel the identifier does not name")
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("id", "other")), "an id the identifier does not name")
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("amount", "0")), "nothing to buy")
        assertNull(GroupLightningAuthorisationEvent.readBuyLiquidity(with("maxfee", "-1")), "a negative fee cap")

        // Two purchases for one channel are two events, not one replacing the other.
        val again = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, 25_000, 500_000, 7_500).getOrThrow()
        assertNotEquals(GroupLightningAuthorisationEvent.template(buy).tags.first { it[0] == "d" }[1], GroupLightningAuthorisationEvent.template(again).tags.first { it[0] == "d" }[1])

        val summary = ProposedEvent.summarize(Event("", "", 0, GroupLightningAuthorisationEvent.KIND, tags, "", ""))
        assertEquals("Add 500,000 sats of inbound liquidity", summary.label)
        assertTrue("7,500 sats" in summary.detail && "after a year" in summary.detail, summary.detail)
    }

    @Test
    fun `once the group signs, every member reads the open purchase, until it expires`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val buy = GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, 25_000, 200_000, 4_000).getOrThrow()
        fixture.signSession(all, all[2], GroupLightningAuthorisations.proposeBuyLiquidity(all[2].db, all[2].room, all[2].publicKey, buy).id)
        all.forEach { d ->
            val mine = GroupLightningWalletManager.walletFor(d.db, fixture.roomId, fixture.chain)!!
            assertEquals(listOf(buy), GroupLightningAuthorisations.liquidityPurchases(d.db, mine), "device ${d.index}")
            assertTrue(GroupLightningAuthorisations.liquidityPurchases(d.db, mine, Clock.System.now() + 4.days).isEmpty(), "device ${d.index}: expired")
            // A purchase is not a payment budget, nor a close.
            assertTrue(GroupLightningAuthorisations.budgets(d.db, mine).isEmpty())
            assertTrue(GroupLightningAuthorisations.channelActions(d.db, fixture.roomId, mine.statement).isEmpty())
        }
    }

    @Test
    fun `a purchase past what a channel may hold is refused before anyone is asked`() = runBlocking<Unit> {
        val (_, wallet) = opened()
        fun problem(capacity: Long, amount: Long) =
            (GroupLightningAuthorisations.prepareBuyLiquidity(wallet, channelId, capacity, amount, 1_000).exceptionOrNull() as? GroupLightningAuthorisations.LiquidityProblemException)?.problem
        assertEquals(GroupLightningAuthorisations.LiquidityProblem.NothingToBuy, problem(25_000, 0))
        val cap = GroupLightningRollout.channelCapFor(fixture.chain)
        if (cap == null) {
            assertNull(problem(25_000, 10_000_000), "no cap off mainnet")
        } else {
            assertNull(problem(25_000, cap.sat - 25_000), "up to the cap exactly")
            assertIs<GroupLightningAuthorisations.LiquidityProblem.OverTheCap>(problem(25_000, cap.sat - 24_999))
        }
    }
}

class GroupLightningLiquidityJvmTest : GroupLightningLiquidityCases(Chain.Regtest)

class GroupLightningLiquidityMainnetJvmTest : GroupLightningLiquidityCases(Chain.Mainnet)
