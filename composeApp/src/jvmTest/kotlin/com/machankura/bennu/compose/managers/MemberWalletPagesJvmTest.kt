package com.machankura.bennu.compose.managers

import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.managers.GroupFixtures.fileKeyState
import com.machankura.bennu.compose.managers.GroupFixtures.guardedCertificate
import com.machankura.bennu.compose.managers.GroupFixtures.keyMaterial
import com.machankura.bennu.compose.managers.GroupFixtures.roomOf
import com.machankura.bennu.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.bennu.compose.managers.GroupFixtures.signingRoom
import com.machankura.bennu.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.bennu.compose.nostr.wallet.MemberWalletCertificateEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the group's page and the person's page say about member wallets: the
 * group lists its members' wallets apart from its subgroups, with the requests
 * still waiting for a signer, and the person's page lists every group here that
 * guards them.
 */
class MemberWalletPagesJvmTest {
    private val db: BennuDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val parent = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val groupRoom = roomOf(group)
    private val parentRoom = roomOf(parent)

    private val alice = privateKey(0xa11ce5)
    private val alicePublicKey = alice.xOnlyPublicKey().value.toHex()
    private val bob = privateKey(0xb0b5)
    private val bobPublicKey = bob.xOnlyPublicKey().value.toHex()

    private suspend fun certify(requestJson: String): Event {
        val template = MemberWalletCertificateEvent.template(Event.fromJson(requestJson))
        val signed = signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(signed, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))
        return signed
    }

    @Test
    fun `the group lists its members' wallets, certified and waiting, and never as subgroups`(): Unit = runBlocking {
        GroupFixtures.seedProfile(db, alicePublicKey, name = "alice")
        signingRoom(db, group, members = listOf(alicePublicKey, bobPublicKey), holdsShare = false)
        fileKeyState(db, group, guardedCertificate(parent, group))

        val certified = MemberWalletManager.request(db, groupRoom, alice, chain, label = "Savings")
        certify(certified.requestJson)
        val waiting = MemberWalletManager.request(db, groupRoom, bob, chain, label = null)

        val listed = MemberWalletManager.memberWalletsOf(db, groupRoom)
        assertEquals(listOf(certified.walletId to true, waiting.walletId to false), listed.map { it.walletId to it.isCertified })
        assertEquals("alice", listed.first().ownerName)
        assertEquals("Savings", listed.first().label)
        assertEquals(bobPublicKey, listed.last().ownerPublicKey)
        assertTrue(SubgroupManager.subgroupsOf(db, groupRoom).isEmpty())
    }

    @Test
    fun `the person's page lists every group here that guards them`(): Unit = runBlocking {
        signingRoom(db, group, members = listOf(alicePublicKey))
        fileKeyState(db, group, guardedCertificate(parent, group))
        signingRoom(db, parent, members = listOf(alicePublicKey), holdsShare = false, subject = "#Root")
        fileKeyState(db, parent)

        val wallet = MemberWalletManager.request(db, groupRoom, alice, chain, label = null)
        val certificate = certify(wallet.requestJson)
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = "9".repeat(64), publicKey = alicePublicKey, kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_100), tags = arrayOf(arrayOf("h", parentRoom)),
                content = certificate.toJson(), chatRoomId = parentRoom,
            )
        )

        val here = MemberWalletManager.guardedByGroupsHere(db, GroupFixtures.owner, alicePublicKey)
        assertEquals(setOf(groupRoom, parentRoom), here.map { it.wallet.ancestorChatRoomId }.toSet())
        assertEquals(true, here.single { it.wallet.ancestorChatRoomId == groupRoom }.canSpend, "a share of the group's key")
        assertEquals(false, here.single { it.wallet.ancestorChatRoomId == parentRoom }.canSpend, "none of the root's")
        assertEquals("#Root", here.single { it.wallet.ancestorChatRoomId == parentRoom }.guardianName)
        assertTrue(MemberWalletManager.guardedByGroupsHere(db, GroupFixtures.owner, bobPublicKey).isEmpty())
    }
}
