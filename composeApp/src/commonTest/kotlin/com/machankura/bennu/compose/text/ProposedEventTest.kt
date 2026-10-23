package com.machankura.bennu.compose.text

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the events a group signs are said to the member signing them.
 *
 * These are read on the signing screen and in the room's list of proposals, and
 * the same words in both is the point of there being one mapping. What is
 * pinned is that a kind the app has no words for is said by its number rather
 * than described wrongly.
 */
class ProposedEventTest {

    @Test
    fun `a kind with no words of its own keeps its number`() {
        // Refusing to describe an event is better than describing it wrongly.
        val summary = ProposedEvent.summarize(event(30023, content = "An article"))

        assertEquals("Event of kind 30023", summary.label)
        assertEquals("An article", summary.detail)
    }

    @Test
    fun `a profile, a post and a relay list are no longer described`() {
        // The group's nostr profile, posts and relay lists were removed, and with
        // them the words for what they were; a proposal for one from an older
        // build is said like any other kind the app does not know.
        listOf(MetadataEvent.KIND, TextNoteEvent.KIND, AdvertisedRelayListEvent.KIND).forEach { kind ->
            assertEquals("Event of kind $kind", ProposedEvent.summarize(event(kind)).label)
        }
    }

    private fun event(
        kind: Int,
        tags: Array<Array<String>> = emptyArray(),
        content: String = "",
    ) = Event(
        id = "a".repeat(64),
        pubKey = "b".repeat(64),
        createdAt = 1_700_000_000,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128),
    )
}
