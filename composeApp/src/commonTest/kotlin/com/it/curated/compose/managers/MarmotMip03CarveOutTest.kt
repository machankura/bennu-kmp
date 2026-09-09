package com.it.curated.compose.managers

import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nip17Dm.messages.ChatMessageEvent
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one hole deliberately left in MIP-03's author check, and its edges.
 *
 * MIP-03 requires an inner application event's pubkey to equal the MLS sender's credential
 * identity, which is what stops a member minting events attributed to somebody else. A
 * gift wrap cannot satisfy it -- its pubkey is a throwaway key that names nobody -- so
 * kind:1059 is exempt, and the authorship claim moves to the signed seal inside.
 *
 * The exemption is the most dangerous line in this feature. Widened to another kind, or
 * stripped of its kind guard, it hands every member of every group the ability to publish
 * events as anybody, and nothing else in the pipeline would notice. These tests exist so
 * that widening it fails here rather than in a group.
 */
class MarmotMip03CarveOutTest {
    private val sender = "a".repeat(64)
    private val somebodyElse = "b".repeat(64)
    private val throwaway = "c".repeat(64)

    @Test
    fun `an ordinary message from its own author is accepted`() {
        assertNull(MarmotInboundManager.mip03Rejection(ChatEvent.KIND, sender, sender))
    }

    @Test
    fun `an ordinary message claiming somebody else is rejected`() {
        val reason = MarmotInboundManager.mip03Rejection(ChatEvent.KIND, somebodyElse, sender)

        assertNotNull(reason)
        assertTrue(reason.startsWith("MIP-03"), "expected the MIP-03 rejection, got: $reason")
    }

    @Test
    fun `a gift wrap is accepted despite naming nobody`() {
        // The carve-out. The wrap's pubkey is a throwaway key and matches no member, which
        // is the point of it; what authenticates the sender is the MLS frame, and what
        // authenticates the author is the seal inside. See MarmotDirectMessage.classify.
        assertNull(MarmotInboundManager.mip03Rejection(GiftWrapEvent.KIND, throwaway, sender))
    }

    @Test
    fun `the carve-out does not extend to any other kind`() {
        // Every kind carried as an application payload today, plus the seal and rumor
        // kinds a gift wrap contains -- none of which should ever arrive unwrapped, and
        // all of which would be a way to launder an author if the guard were widened.
        val kinds = listOf(
            ChatEvent.KIND,
            ChatMessageEvent.KIND,
            SealedRumorEvent.KIND,
            GiftWrapEvent.KIND - 1,
            GiftWrapEvent.KIND + 1,
            0,
            1,
        )

        kinds.forEach { kind ->
            assertNotNull(
                MarmotInboundManager.mip03Rejection(kind, somebodyElse, sender),
                "kind $kind was allowed to claim an author it does not own",
            )
        }
    }

    @Test
    fun `a sender with no credential identity is rejected, wrap or not`() {
        // Required rather than merely compared: with no identity there is nothing to
        // attribute a line to, and for a gift wrap there is nothing to check the seal
        // against either. Both kinds must fail, not just the one that does a comparison.
        assertNotNull(MarmotInboundManager.mip03Rejection(ChatEvent.KIND, sender, null))
        assertNotNull(MarmotInboundManager.mip03Rejection(GiftWrapEvent.KIND, throwaway, null))
    }
}
