package com.machankura.bennu.compose.managers

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
}
