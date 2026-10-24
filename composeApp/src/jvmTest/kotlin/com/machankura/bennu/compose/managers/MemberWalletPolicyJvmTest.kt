package com.machankura.bennu.compose.managers

import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import com.machankura.bennu.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.bennu.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.secp256k1.Hex
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.managers.GroupFixtures.filePolicy
import com.machankura.bennu.compose.managers.GroupFixtures.keyMaterial
import com.machankura.bennu.compose.managers.GroupFixtures.memberWalletPolicy
import com.machankura.bennu.compose.managers.GroupFixtures.roomOf
import com.machankura.bennu.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.bennu.compose.text.ProposedEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A group's policy on its members' wallets, as the group's own devices read it
 * -- `docs/member-wallet-delay.md`.
 */
class MemberWalletPolicyJvmTest {
    private val db: BennuDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val group = keyMaterial("6a0b")
    private val stranger = keyMaterial("5e7a")
    private val groupRoom = roomOf(group)

    // ---- Phase 2: the group files it ---------------------------------------

    @Test
    fun `a group that never signed a policy has none`(): Unit = runBlocking {
        assertNull(MemberWalletManager.policyFor(db, groupRoom))
    }

    @Test
    fun `the newest stored policy decides, open or closed, either way round`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, groupRoom)
        filePolicy(db, group, memberWalletPolicy(group, 144, createdAt = 1_700_000_000))
        filePolicy(db, group, memberWalletPolicy(group, null, createdAt = 1_700_000_100))
        assertIs<MemberWalletPolicyEvent.Policy.Closed>(MemberWalletManager.policyFor(db, groupRoom))

        filePolicy(db, group, memberWalletPolicy(group, 1_008, createdAt = 1_700_000_200))
        assertEquals(1_008L, assertIs<MemberWalletPolicyEvent.Policy.Open>(MemberWalletManager.policyFor(db, groupRoom)).delay)
    }

    @Test
    fun `a policy another room signed is not this group's`(): Unit = runBlocking {
        GroupFixtures.seedRoom(db, groupRoom)
        // Filed under this room, signed by another: nothing a device can act on.
        val forged = memberWalletPolicy(stranger, 6)
        db.groupSignedEventDao().upsert(
            com.machankura.bennu.compose.database.model.GroupSignedEvent.fromEvent(forged, chatRoomId = groupRoom, derivationPath = null, frostSigningSessionId = null)
        )
        assertNull(MemberWalletManager.policyFor(db, groupRoom))
    }

    @Test
    fun `a signer reads what a policy decides, and what closing leaves alone`() {
        val opened = memberWalletPolicy(group, 144)
        assertEquals("Members' guarded wallets", ProposedEvent.summarize(opened).label)
        assertEquals("this group after 144 blocks, about 1 day · for wallets asked for from now on", ProposedEvent.summarize(opened).detail)

        val closed = memberWalletPolicy(group, null)
        assertEquals("no new ones · wallets already guarded stay guarded", ProposedEvent.summarize(closed).detail)
    }

    // ---- Phase 4: the member builds under it -------------------------------

    private val parent = keyMaterial("9a9e")
    private val chain = Chain.Regtest
    private val member = PrivateKey(ByteVector32(Hex.decode("3e3be7".padStart(64, '0'))))
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()

    private suspend fun asMember() {
        GroupFixtures.signingRoom(db, group, members = listOf(memberPublicKey), holdsShare = false)
        GroupFixtures.fileKeyState(db, group, GroupFixtures.guardedCertificate(parent, group))
    }

    @Test
    fun `a member asks only a group that has opened`(): Unit = runBlocking {
        asMember()
        assertEquals(MemberWalletManager.Refusal.NOT_OFFERED, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertFailsWith<IllegalStateException> { MemberWalletManager.request(db, groupRoom, member, chain, label = null) }
        assertNull(MemberWalletManager.delaysOf(db, groupRoom))

        filePolicy(db, group, memberWalletPolicy(group, null))
        assertEquals(MemberWalletManager.Refusal.CLOSED, MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertFailsWith<IllegalStateException> { MemberWalletManager.request(db, groupRoom, member, chain, label = null) }
        assertTrue(db.memberWalletDao().getByOwner(memberPublicKey).isEmpty(), "a refused request makes no key")
    }

    @Test
    fun `a request is built at the group's delay and carries the policy it was built under`(): Unit = runBlocking {
        asMember()
        val policy = memberWalletPolicy(group, 4_320)
        filePolicy(db, group, policy)

        assertNull(MemberWalletManager.refusal(db, groupRoom, canSign = true, chain))
        assertEquals(listOf(4_320L, GuardianDerivation.DEFAULT_DELAY), MemberWalletManager.delaysOf(db, groupRoom), "the parent's keeps its own")

        val wallet = MemberWalletManager.request(db, groupRoom, member, chain, label = null)
        val verified = assertNotNull(MemberWalletRequestEvent.verified(Event.fromJson(wallet.requestJson)))
        assertEquals(4_320L, verified.reading.guardians.first().delay)
        assertEquals(policy.id, verified.policy?.event?.id)
    }

    @Test
    fun `a request whose leaf disagrees with the policy it carries does not check out`(): Unit = runBlocking {
        asMember()
        val walletKey = MemberWalletDerivation.generate()
        val walletId = MemberWalletDerivation.walletId(walletKey.publicKey())
        val atDefault = GuardianDerivation.grant(thresholdKeyOf(group), groupRoom, emptyList(), walletId)
        val account = MemberWalletDerivation.account(walletKey.publicKey(), chain, atDefault)
        fun build(policy: Event?) = MemberWalletRequestEvent.build(member, groupRoom, walletKey, account, label = null, policy = policy)

        assertNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(group, 4_320))), "built at 12 under a policy of 4,320")
        assertNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(group, null))), "built under a closed policy")
        assertNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(stranger, 12))), "under another group's policy")
        assertNotNull(MemberWalletRequestEvent.verified(build(memberWalletPolicy(group, 12))))
        // A request from before policies still reads: old certificates embed one.
        assertNotNull(MemberWalletRequestEvent.verified(build(null)))
    }
}
