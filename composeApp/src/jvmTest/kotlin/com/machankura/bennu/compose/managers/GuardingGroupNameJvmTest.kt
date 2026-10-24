package com.machankura.bennu.compose.managers

import androidx.room3.Room
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.builder.getRoomDatabase
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.MarmotInnerEvent
import com.machankura.bennu.compose.managers.GroupFixtures.birthCertificate
import com.machankura.bennu.compose.managers.GroupFixtures.fileKeyState
import com.machankura.bennu.compose.managers.GroupFixtures.keyMaterial
import com.machankura.bennu.compose.managers.GroupFixtures.roomOf
import com.machankura.bennu.compose.managers.GroupFixtures.signedAsRoom
import com.machankura.bennu.compose.managers.GroupFixtures.signingRoom
import com.machankura.bennu.compose.managers.GroupFixtures.thresholdKeyOf
import com.machankura.bennu.compose.managers.GuardianManager.GuardedSubject
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The names a member's wallet is shown under in a group above it: the group
 * that directly guards it, and the branch of the holder's tree it sits under --
 * each from a birth certificate this device can check, whoever handed it over,
 * and never from one it cannot. `docs/member-wallet-guarding-group.md`, Phase 2.
 *
 * The tree is R ⊃ P ⊃ M, and M guards Alice's Savings.
 */
class GuardingGroupNameJvmTest {
    private val db: BennuDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<BennuDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val chain = Chain.Regtest

    private fun privateKey(seed: Long): PrivateKey = PrivateKey(ByteVector32(Hex.decode(seed.toString(16).padStart(64, '0'))))

    private val r = keyMaterial("7007")
    private val p = keyMaterial("9a9e")
    private val m = keyMaterial("6a0b")
    private val stranger = keyMaterial("57a9")
    private val rRoom = roomOf(r)
    private val pRoom = roomOf(p)
    private val mRoom = roomOf(m)

    private val member = privateKey(0x3e3be7)
    private val memberPublicKey = member.xOnlyPublicKey().value.toHex()
    private val walletPrivateKey = privateKey(0xa11ce)
    private val walletId = MemberWalletDerivation.walletId(walletPrivateKey.publicKey())

    private val pGrant = GuardianDerivation.grant(thresholdKeyOf(r), rRoom, emptyList(), pRoom)
    private val mGrant = GuardianDerivation.grant(thresholdKeyOf(p), pRoom, pGrant, mRoom)
    private val savingsGrant = GuardianDerivation.grant(thresholdKeyOf(m), mRoom, mGrant, walletId)

    /** R's certificate for P, and P's for M, as their founding named them. */
    private val pCertificate = birthCertificate(r, p, pGrant, name = "#P")
    private val mCertificate = birthCertificate(p, m, mGrant, name = "#M")

    /** M's certificate for Alice's Savings, guarded by `[M, P, R]`. */
    private val savings: Event = run {
        val request = MemberWalletRequestEvent.build(
            memberPrivateKey = member,
            groupChatRoomId = mRoom,
            walletPrivateKey = walletPrivateKey,
            account = MemberWalletDerivation.account(walletPrivateKey.publicKey(), chain, savingsGrant),
            label = "Savings",
            createdAt = 1_700_000_000,
        )
        val template = MemberWalletCertificateEvent.template(request)
        signedAsRoom(m, template.kind, template.tags, template.content, template.createdAt)
    }

    /** This device in [material]'s room, holding its key state. */
    private suspend fun inRoom(material: KeyMaterial, certificate: Event? = null, subject: String = "#" + roomOf(material).take(4)) {
        signingRoom(db, material, members = listOf(memberPublicKey), subject = subject)
        fileKeyState(db, material, certificate)
    }

    /** [certificate] as one of [signer]'s own signed events here. */
    private suspend fun fileSigned(certificate: Event, signer: String) =
        db.groupSignedEventDao().upsert(GroupSignedEvent.fromEvent(certificate, chatRoomId = signer, derivationPath = SharedKeyDerivation.formatPath(GroupFixtures.path), frostSigningSessionId = null))

    private suspend fun noticeIn(room: String, statement: Event) =
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = statement.id.take(60) + "0000",
                publicKey = memberPublicKey,
                kind = GuardedWalletNoticeEvent.KIND,
                createdAt = Instant.fromEpochSeconds(1_700_000_100),
                tags = arrayOf(arrayOf("h", room)),
                content = statement.toJson(),
                chatRoomId = room,
            )
        )

    private suspend fun heldByR(): GuardedSubject.Member {
        val listed = GuardianManager.guardedWalletsOf(db, rRoom).single()
        assertEquals(mRoom, listed.directGuardianChatRoomId)
        return assertIs<GuardedSubject.Member>(listed.subject)
    }

    @Test
    fun `a device in R and P names M from the certificate P signed`(): Unit = runBlocking {
        inRoom(r)
        inRoom(p, pCertificate)
        fileSigned(pCertificate, signer = rRoom)
        fileSigned(mCertificate, signer = pRoom)
        noticeIn(rRoom, savings)

        val subject = heldByR()
        assertEquals("#M", subject.guardianName)
        assertEquals("#P", subject.branchName)
    }

    @Test
    fun `a device in R only knows its own branch, and not the group below it`(): Unit = runBlocking {
        inRoom(r)
        fileSigned(pCertificate, signer = rRoom)
        noticeIn(rRoom, savings)

        val subject = heldByR()
        assertNull(subject.guardianName)
        assertEquals("#P", subject.branchName)
    }

    @Test
    fun `a certificate for M that P did not sign names nothing`(): Unit = runBlocking {
        inRoom(r)
        inRoom(p, pCertificate)
        fileSigned(pCertificate, signer = rRoom)
        // Filed under P, signed by a stranger: it does not certify M as P's child.
        fileSigned(birthCertificate(stranger, m, mGrant, name = "#Impostor"), signer = pRoom)
        noticeIn(rRoom, savings)

        assertNull(heldByR().guardianName)
    }

    @Test
    fun `a renamed M is still shown by its founding name`(): Unit = runBlocking {
        inRoom(r)
        inRoom(m, mCertificate, subject = "#Renamed")
        fileSigned(pCertificate, signer = rRoom)
        noticeIn(rRoom, savings)

        assertEquals("#M", heldByR().guardianName)
    }

    @Test
    fun `with no certificate to check, the room's name here is used`(): Unit = runBlocking {
        inRoom(r)
        inRoom(m, subject = "#Here")
        noticeIn(rRoom, savings)

        assertEquals("#Here", heldByR().guardianName)
    }
}
