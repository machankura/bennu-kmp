package com.machankura.bennu.compose.text

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How the events a group signs about itself are said to the member signing them.
 *
 * These are read on the signing screen, in the room's list of proposals and now
 * on the paste screen before proposing, and the same words in all three is the
 * point of there being one mapping. What is pinned is that each kind is said as
 * the thing it is and by the part of it a signer is actually deciding about --
 * a profile by its name, a post by its words, a relay list by its hosts -- and
 * never as "kind 0" over a wall of JSON.
 */
class ProposedEventTest {

    @Test
    fun `a profile is said by its name and what it says about itself`() {
        val summary = ProposedEvent.summarize(
            event(MetadataEvent.KIND, content = """{"name":"Translation collective","about":"We translate hard books."}""")
        )

        assertEquals("Nostr profile", summary.label)
        assertEquals("Translation collective · We translate hard books.", summary.detail)

        // Empty and unreadable are different things, and a signer should know which.
        assertEquals("An empty profile", ProposedEvent.summarize(event(MetadataEvent.KIND, content = "{}")).detail)
        assertEquals("Unreadable profile", ProposedEvent.summarize(event(MetadataEvent.KIND, content = "not json")).detail)
    }

    @Test
    fun `a post is said by its words`() {
        val summary = ProposedEvent.summarize(event(TextNoteEvent.KIND, content = "We have finished the first chapter."))

        assertEquals("Post", summary.label)
        assertEquals("We have finished the first chapter.", summary.detail)
    }

    @Test
    fun `a relay list is said by which list it is and its hosts`() {
        val summary = ProposedEvent.summarize(
            event(
                AdvertisedRelayListEvent.KIND,
                tags = arrayOf(arrayOf("r", "wss://relay.one.example"), arrayOf("r", "wss://relay.two.example", "read"))
            )
        )

        assertEquals("General relay list", summary.label)
        assertEquals("relay.one.example, relay.two.example", summary.detail)

        // An empty list is a withdrawal, which is worth saying in as many words.
        assertEquals("No relays", ProposedEvent.summarize(event(AdvertisedRelayListEvent.KIND)).detail)
    }

    @Test
    fun `a kind with no words of its own keeps its number`() {
        // Refusing to describe an event is better than describing it wrongly.
        val summary = ProposedEvent.summarize(event(30023, content = "An article"))

        assertEquals("Event of kind 30023", summary.label)
        assertEquals("An article", summary.detail)
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
