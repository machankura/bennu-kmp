package com.machankura.bennu.compose.managers

import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.managers.GroupFixtures.birthCertificate
import com.machankura.bennu.compose.managers.GroupFixtures.fileKeyState
import com.machankura.bennu.compose.managers.GroupFixtures.keyMaterial
import com.machankura.bennu.compose.managers.GroupFixtures.roomOf
import com.machankura.bennu.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.bennu.compose.managers.GroupFixtures.signingRoom
import com.machankura.bennu.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GuardedWalletNoticeEvent
import com.machankura.bennu.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.MemberWalletRequestEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Telling the groups above about a guarded wallet, as a function: upward only,
 * never a group that already holds it, never as another profile, and -- when
 * a wallet has only just arrived -- only by the device elected to send at once.
 * `docs/guarded-wallet-forwarding.md`, Phase 2.
 *
 * A root `R`, its guarded subgroup `M`, and `M`'s guarded subgroup `G`, which
 * guards a member's wallet: the wallet's guardians are `G`, `M` and `R`.
 */
class GuardedWalletForwardJvmTest {
    private val db: BennuDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val root = keyMaterial("7007")
    private val middle = keyMaterial("9a9e")
    private val group = keyMaterial("6a0b")
    private val rootRoom = roomOf(root)
    private val middleRoom = roomOf(middle)
    private val groupRoom = roomOf(group)

    /** `M`'s certificate from `R`, and `G`'s from `M` with `R`'s key passed one step further. */
    private val middleCertificate = birthCertificate(root, middle, GuardianDerivation.grant(thresholdKeyOf(root), rootRoom, emptyList(), middleRoom))
    private val groupCertificate = birthCertificate(
        middle, group,
        GuardianDerivation.grant(thresholdKeyOf(middle), middleRoom, SubgroupBirthCertificateEvent.parseGuardians(middleCertificate.tags)!!, groupRoom),
    )

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    private val certificate: Event = run {
        val grant = GuardianDerivation.grant(thresholdKeyOf(group), groupRoom, SubgroupBirthCertificateEvent.parseGuardians(groupCertificate.tags)!!, walletId)
        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = groupRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, grant),
            label = null,
            createdAt = 1_700_000_000,
        )
        val template = MemberWalletCertificateEvent.template(request)
        signedAsRoom(group, template.kind, template.tags, template.content, template.createdAt)
    }

    /** A member whose key sorts above this device's own, so this device stays the elected sender. */
    private val laterMember = "f".repeat(64)

    /** This device in [material]'s room, with its key state, and [members] beside it. */
    private suspend fun inRoom(material: KeyMaterial, certificate: Event?, members: List<String> = listOf(memberPublicKey, laterMember)) {
        signingRoom(db, material, members = members, subject = "#" + roomOf(material).take(4), holdsShare = false)
        fileKeyState(db, material, certificate)
    }

    private suspend fun inAllThree() {
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        inRoom(root, null)
    }

    private suspend fun signedInGroup() =
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(certificate, chatRoomId = groupRoom, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

    private suspend fun noticesIn(chatRoomId: String): List<Event> =
        db.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GuardedWalletNoticeEvent.KIND)).mapNotNull { Event.fromJsonOrNull(it.content) }

    @Test
    fun `from the group that signed it, every group above is told, and the group is not`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()

        val told = GuardianManager.forward(db, certificate, groupRoom, whenElected = false).map { it.chatRoom.id }
        assertEquals(listOf(middleRoom, rootRoom), told)
        assertEquals(listOf(certificate.id), noticesIn(middleRoom).map { it.id })
        assertTrue(noticesIn(groupRoom).isEmpty(), "the group holds what it signed")
        assertTrue(GuardianManager.told(db, groupRoom, certificate.id), "a signed event counts as told")

        assertTrue(GuardianManager.forward(db, certificate, groupRoom, whenElected = false).isEmpty(), "told once")
    }

    @Test
    fun `from a group above, only the groups above it`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()
        // As though the middle group had been told by somebody else, and the
        // root not yet.
        GuardianManager.share(db, certificate, listOfNotNull(db.chatRoomDao().findChatRoomById(middleRoom)), memberPublicKey)

        assertEquals(listOf(rootRoom), GuardianManager.forward(db, certificate, middleRoom, whenElected = false).map { it.chatRoom.id })
        assertTrue(GuardianManager.forward(db, certificate, rootRoom, whenElected = false).isEmpty(), "nothing goes back down")
    }

    @Test
    fun `the button offers only what goes up`(): Unit = runBlocking {
        inAllThree()
        signedInGroup()
        GuardianManager.share(db, certificate, listOfNotNull(db.chatRoomDao().findChatRoomById(middleRoom), db.chatRoomDao().findChatRoomById(rootRoom)), memberPublicKey)

        // The root holds it as a notice, the middle group too, and the group signed it.
        assertTrue(GuardianManager.shareableFrom(db, rootRoom, chain).isEmpty(), "the root has nobody above it to tell")
        assertTrue(GuardianManager.shareableFrom(db, middleRoom, chain).isEmpty())
        assertTrue(GuardianManager.shareableFrom(db, groupRoom, chain).isEmpty())
    }

    @Test
    fun `a group above that belongs to another profile on the device is never told`(): Unit = runBlocking {
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        signedInGroup()
        val other = "0f".repeat(32)
        GroupFixtures.seedProfile(db, other)
        val middleRow = db.chatRoomDao().findChatRoomById(middleRoom)!!.chatRoom
        db.chatRoomDao().upsert(middleRow.copy(userPublicKey = other))

        assertTrue(GuardianManager.forward(db, certificate, groupRoom, whenElected = false).isEmpty())
        assertTrue(noticesIn(middleRoom).isEmpty())
    }

    @Test
    fun `when a wallet has only just arrived, only the elected device sends`(): Unit = runBlocking {
        // A member whose key sorts below this device's sits in both rooms.
        val earlier = "00".repeat(31) + "01"
        inRoom(group, groupCertificate, members = listOf(memberPublicKey, earlier))
        inRoom(middle, middleCertificate, members = listOf(earlier))
        signedInGroup()

        assertTrue(GuardianManager.forward(db, certificate, groupRoom, whenElected = true).isEmpty(), "the earlier member sends")
        assertEquals(listOf(middleRoom), GuardianManager.forward(db, certificate, groupRoom, whenElected = false).map { it.chatRoom.id }, "and the catch-up sends what is left")
    }

    @Test
    fun `the elected device sends at once`(): Unit = runBlocking {
        inRoom(group, groupCertificate)
        inRoom(middle, middleCertificate)
        signedInGroup()

        assertEquals(listOf(middleRoom), GuardianManager.forward(db, certificate, groupRoom, whenElected = true).map { it.chatRoom.id })
    }

    @Test
    fun `nothing it does not believe is forwarded anywhere`(): Unit = runBlocking {
        inAllThree()
        val forged = Event(certificate.id, certificate.pubKey, certificate.createdAt, certificate.kind, certificate.tags, certificate.content, "0".repeat(128))
        assertTrue(GuardianManager.forward(db, forged, groupRoom, whenElected = false).isEmpty())
        assertFalse(noticesIn(middleRoom).isNotEmpty())
    }
}
