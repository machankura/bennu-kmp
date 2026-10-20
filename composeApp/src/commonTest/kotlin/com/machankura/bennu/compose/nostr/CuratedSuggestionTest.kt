package com.machankura.bennu.compose.nostr

import com.machankura.bennu.compose.database.GENESIS_AT
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.NostrEvent
import com.machankura.bennu.compose.database.model.Profile
import com.machankura.bennu.compose.database.model.intermdiate.LocalNostrEvent
import com.machankura.bennu.compose.nostr.curated.CuratedField
import com.machankura.bennu.compose.nostr.curated.CuratedFieldConfig
import com.machankura.bennu.compose.nostr.curated.CuratedFieldType
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The queue of a list the group curates, read off whatever this device has
 * stored for the coordinate.
 *
 * The rows come from a filter on kind and `a` tag, which is broader than the
 * queue in three ways this reading has to close: an event that does not satisfy
 * the schema, a kind 31890 signed by somebody other than the group, and an
 * older version of an entry its author has since replaced. And one thing it
 * has to add: whether the group has already taken a suggestion up, which is a
 * canonical entry sharing its `d`.
 *
 * Also what to call a stranger. A profile that has arrived is a name; the
 * placeholder the indexer writes on first sight is not, and a queue full of
 * "LOADING..." would be a queue of nobody.
 */
class CuratedSuggestionTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val films = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = "c".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        schema = CuratedSchema(
            identifier = "films",
            title = "Film suggestion",
            name = "Films worth translating",
            description = "Films the group would subtitle.",
            visibility = CuratedVisibility.Public,
            fields = listOf(
                CuratedField.IDENTIFIER,
                CuratedField.TITLE,
                CuratedField(
                    "watchUrl",
                    CuratedFieldType.Url,
                    isRequired = true,
                    config = CuratedFieldConfig(tag = "r", marker = "watch")
                ),
            ),
        ).normalized(),
    )

    private val coordinate = films.coordinate()

    @Test
    fun `the queue is every suggestion that satisfies the schema newest first`() {
        val queue = CuratedSuggestion.queueOf(
            films,
            listOf(
                stored(suggestion(ada, "older", "The older one", 1_700_000_000)),
                stored(suggestion(bob, "newer", "The newer one", 1_700_000_900)),
                // No watch link, which the schema requires: not shown with the
                // gap blanked out, not shown at all.
                stored(suggestion(ada, "broken", "The broken one", 1_700_000_500, watchUrl = null)),
                // Another list's reply with this one's identifier. Not this queue.
                stored(suggestion(ada, "elsewhere", "Somebody else's", 1_700_000_600, root = "31889:${"e".repeat(64)}:films")),
                // A text note that happens to carry the coordinate.
                stored(suggestion(ada, "note", "A note", 1_700_000_700).copy(kind = 1)),
            )
        )

        assertEquals(listOf("The newer one", "The older one"), queue.map { it.entry.title })
        assertTrue(queue.none { it.isCurated })
    }

    @Test
    fun `an entry its author has replaced shows once as the newer version`() {
        val queue = CuratedSuggestion.queueOf(
            films,
            listOf(
                stored(suggestion(ada, "rise", "The Rise and Rise of Bitcoin", 1_700_000_000)),
                stored(suggestion(ada, "rise", "The Rise and Rise of Bitcoin (2014)", 1_700_000_900)),
                // The same `d` under another key is another entry: coordinates
                // carry the author.
                stored(suggestion(bob, "rise", "Bob's rise", 1_700_000_100)),
            )
        )

        assertEquals(
            listOf("The Rise and Rise of Bitcoin (2014)", "Bob's rise"),
            queue.map { it.entry.title }
        )
    }

    @Test
    fun `a suggestion the group has taken up is marked and the group's entry is not in the queue`() {
        val queue = CuratedSuggestion.queueOf(
            films,
            listOf(
                stored(suggestion(ada, "rise", "The Rise and Rise of Bitcoin", 1_700_000_000)),
                stored(suggestion(bob, "magic", "Magic Money", 1_700_000_100)),
                stored(canonical(chatRoomId, "rise", "The Rise and Rise of Bitcoin", 1_700_000_900)),
                // A kind 31890 by somebody who is not the curator is nobody's
                // list, and marks nothing.
                stored(canonical(bob, "magic", "Magic Money", 1_700_000_950)),
            )
        )

        assertEquals(listOf("Magic Money", "The Rise and Rise of Bitcoin"), queue.map { it.entry.title })
        assertEquals(
            mapOf("Magic Money" to false, "The Rise and Rise of Bitcoin" to true),
            queue.associate { it.entry.title to it.isCurated }
        )
    }

    @Test
    fun `a suggester is named by their profile and by their key until one arrives`() {
        val named = Profile(
            publicKey = ada,
            displayName = "Ada",
            nostrEventId = "e".repeat(64),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        )
        val placeholder = Profile(
            publicKey = bob,
            displayName = "LOADING...",
            nostrEventId = "e".repeat(64),
            createdAt = GENESIS_AT,
        )

        val queue = CuratedSuggestion.queueOf(
            films,
            listOf(
                stored(suggestion(ada, "one", "One", 1_700_000_000), profile = named),
                stored(suggestion(bob, "two", "Two", 1_700_000_100), profile = placeholder),
                stored(suggestion("c".repeat(64), "three", "Three", 1_700_000_200), profile = null),
            )
        )

        assertEquals(
            listOf("cccccccc", "bbbbbbbb", "Ada"),
            queue.map { it.suggesterName() }
        )
        assertFalse(queue.any { it.suggesterName() == "LOADING..." })
    }

    private fun suggestion(
        pubKey: String,
        identifier: String,
        title: String,
        createdAt: Long,
        watchUrl: String? = "https://example.com/watch",
        root: String = coordinate,
    ) = NostrEvent(
        id = createdAt.toString().padStart(60, '0') + pubKey.take(4),
        pubKey = pubKey,
        kind = CuratedSchemaEvent.SUGGESTION_KIND,
        tags = listOfNotNull(
            arrayOf("d", identifier),
            arrayOf("title", title),
            watchUrl?.let { arrayOf("r", it, "watch") },
            arrayOf("a", root, "", "root"),
        ).toTypedArray(),
        content = "",
        sig = "b".repeat(128),
        createdAt = Instant.fromEpochSeconds(createdAt),
    )

    private fun canonical(pubKey: String, identifier: String, title: String, createdAt: Long) =
        suggestion(pubKey, identifier, title, createdAt).copy(kind = CuratedSchemaEvent.CANONICAL_KIND)

    private fun stored(event: NostrEvent, profile: Profile? = null) =
        LocalNostrEvent(nostrEvent = event, profile = profile)
}
