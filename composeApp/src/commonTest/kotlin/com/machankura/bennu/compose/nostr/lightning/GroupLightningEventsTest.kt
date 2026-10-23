package com.machankura.bennu.compose.nostr.lightning

import com.machankura.bennu.compose.nostr.MarmotDirectMessage
import com.machankura.bennu.compose.nostr.chronicle.ChronicleEvent
import com.machankura.bennu.compose.nostr.chronicle.ChronicleRequestEvent
import com.machankura.bennu.compose.nostr.dkg.DkgRitualEvents
import com.machankura.bennu.compose.nostr.frost.FrostSigningEvents
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Lightning wallet's kinds stand apart from every family the inbound path
 * already dispatches on. The FROST family's note says its own separation from
 * the DKG's was "an accident of routing rather than a decision"; this test is
 * the decision, written down where a new kind would break it.
 */
class GroupLightningEventsTest {
    private val taken = DkgRitualEvents.ALL + FrostSigningEvents.ALL + setOf(
        GroupKeyStateEvent.KIND,
        ChronicleEvent.KIND,
        ChronicleRequestEvent.KIND,
        SubgroupBirthCertificateEvent.KIND,
        MarmotDirectMessage.ENVELOPE_KIND,
        GroupWalletStatementEvent.KIND,
    ) + (30300..30312).toSet()   // the nip30303 document kinds

    @Test
    fun `no lightning kind is a kind the app already routes`() {
        assertEquals(emptySet(), GroupLightningEvents.ALL intersect taken)
    }

    @Test
    fun `the family is one unbroken run from 30333 to 30349`() {
        assertEquals((30333..30349).toSet(), GroupLightningEvents.ALL)
    }

    @Test
    fun `the groups partition the family`() {
        val groups = listOf(
            GroupLightningEvents.CEREMONY,
            GroupLightningEvents.CO_SIGNING,
            GroupLightningEvents.WALLET_TRAFFIC,
            GroupLightningEvents.GROUP_SIGNED,
        )
        assertEquals(GroupLightningEvents.ALL.size, groups.sumOf { it.size })
        for (i in groups.indices) for (j in i + 1 until groups.size) {
            assertTrue((groups[i] intersect groups[j]).isEmpty(), "groups $i and $j overlap")
        }
    }
}
