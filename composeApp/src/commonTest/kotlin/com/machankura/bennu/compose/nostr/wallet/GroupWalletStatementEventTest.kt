package com.machankura.bennu.compose.nostr.wallet

import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.managers.GroupWalletDerivation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A wallet statement, read and believed.
 *
 * The statement is a record that the group agreed, not a source of keys, so most
 * of what is here is refusals: a statement is only a wallet when the room signed
 * it *and* the room's threshold key rederives every value it carries.
 */
class GroupWalletStatementEventTest {
    private fun key(seed: Int): String =
        PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0')))).publicKey().toString()

    private val thresholdKey = key(0x1c0ffee)
    private val otherThresholdKey = key(0xdecaf)

    /**
     * The room, as a key whose secret the test holds -- `GroupSignedEvent.verifies`
     * asks only that the room's id signed it, and a real FROST signature over the
     * same id is `GroupWalletSpendRoundTest`'s business.
     */
    private val roomKey = PrivateKey(ByteVector32(Hex.decode("07".repeat(32))))
    private val roomId = roomKey.publicKey().xOnly().value.toString()

    private fun signed(tags: Array<Array<String>>, content: String, createdAt: Long = 1_700_000_000, by: PrivateKey = roomKey): GroupSignedEvent {
        val pubKey = by.publicKey().xOnly().value.toString()
        val id = EventHasher.hashId(pubKey, createdAt, GroupWalletStatementEvent.KIND, tags, content)
        val sig = Crypto.signSchnorr(ByteVector32(id), by, null).toString()
        return GroupSignedEvent.fromEvent(
            Event(id, pubKey, createdAt, GroupWalletStatementEvent.KIND, tags, content, sig),
            chatRoomId = roomId,
        )
    }

    private fun statement(account: GroupWalletDerivation.Account, createdAt: Long = 1_700_000_000): GroupSignedEvent {
        val template = GroupWalletStatementEvent.template(account, createdAt)
        return signed(template.tags, template.content, createdAt)
    }

    private val mainnet = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet)

    @Test
    fun `a statement reads back as what was written`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val reading = assertNotNull(GroupWalletStatementEvent.read(template.tags, template.content))

        assertEquals(Chain.Mainnet, reading.chain)
        assertEquals(0L, reading.account)
        assertEquals(mainnet.xpub, reading.xpub)
        assertEquals(mainnet.receiveDescriptor, reading.receiveDescriptor)
        assertEquals(mainnet.changeDescriptor, reading.changeDescriptor)
        assertEquals(mainnet, reading.verifiedAgainst(thresholdKey))
    }

    @Test
    fun `the room's statement opens the room's wallet`() {
        val wallet = assertNotNull(GroupWallet.of(statement(mainnet), thresholdKey))

        assertEquals(mainnet, wallet.account)
        assertEquals(Chain.Mainnet, wallet.chain)
    }

    @Test
    fun `a statement another key rederives is not this room's wallet`() {
        // Signed by the room, every field well formed -- and naming the xpub of a
        // different group. Whoever signed it, it is not a wallet this room's key holds.
        val foreign = GroupWalletDerivation.account(otherThresholdKey, Chain.Mainnet)

        assertNull(GroupWallet.of(statement(foreign), thresholdKey))
    }

    @Test
    fun `an xpub edited after the fact is refused`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val other = GroupWalletDerivation.account(thresholdKey, Chain.Mainnet, account = 1)

        assertNull(GroupWallet.of(signed(template.tags, other.xpub), thresholdKey))
    }

    @Test
    fun `a statement the room did not sign is a member's, not the group's`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val member = PrivateKey(ByteVector32(Hex.decode("09".repeat(32))))

        assertNull(GroupWallet.of(signed(template.tags, template.content, by = member), thresholdKey))
    }

    @Test
    fun `a d tag that disagrees with the fields is not read`() {
        val template = GroupWalletStatementEvent.template(mainnet)
        val tags = template.tags.map { if (it[0] == "d") arrayOf("d", "regtest:0") else it }.toTypedArray()

        assertNull(GroupWalletStatementEvent.read(tags, template.content))
    }

    @Test
    fun `the newest statement for the asked network wins, and another network's is not it`() {
        val regtest = GroupWalletDerivation.account(thresholdKey, Chain.Regtest)
        val older = statement(mainnet, createdAt = 1_700_000_000)
        val newer = statement(mainnet, createdAt = 1_700_000_100)

        val wallet = assertNotNull(
            GroupWallet.newestAmong(listOf(newer, statement(regtest), older), thresholdKey, Chain.Mainnet)
        )
        assertEquals(newer.id, wallet.signedEvent.id)
        assertNull(GroupWallet.newestAmong(listOf(older, newer), thresholdKey, Chain.Signet))
    }

    @Test
    fun `every network name round trips`() {
        listOf(Chain.Mainnet, Chain.Testnet3, Chain.Testnet4, Chain.Signet, Chain.Regtest).forEach {
            assertEquals(it, GroupWalletStatementEvent.chainNamed(GroupWalletStatementEvent.chainName(it)))
        }
    }
}
