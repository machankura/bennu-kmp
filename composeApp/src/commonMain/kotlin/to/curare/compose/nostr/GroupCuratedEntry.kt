package to.curare.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedEntryEvent
import kotlin.time.Instant

/**
 * A canonical entry the group signed: a suggestion it accepted into one of
 * the lists it curates, as the list's own word.
 *
 * Kind 31890 -- see [CuratedEntryEvent] -- authored by the room's own key,
 * which is the one key a list's readers accept a canonical entry from. Where
 * the schema is the group asking and the queue is what strangers answered,
 * this is what the group *decided*: the entries the list stands behind, each
 * one a quorum's signature over a suggestion, edited or not, with a pointer
 * back at who suggested it.
 *
 * ### Read against the list it replies to
 *
 * An entry replies to a schema by coordinate, so the reading needs the
 * group's schemas as well as its signed events: the one whose coordinate the
 * entry's `a` root names is the list it belongs to, and the entry is then
 * checked against that list's fields exactly as `CuratedSuggestion.queueOf`
 * checks a copy that came back off a relay. The two readings agree by
 * construction, so what this screen shows as accepted is what the queue will
 * mark as curated once the entry has been broadcast and read back.
 *
 * An entry replying to a list this device holds no schema for is not shown:
 * without the schema there are no fields to read it by, and no way to say
 * which list it is in.
 *
 * ### One per coordinate
 *
 * The kind is addressable, so re-curating an entry -- to take in a
 * suggester's edit, or to fix the group's own -- is a newer event under the
 * same `d`, and the newest is the entry the way the newest schema is the
 * list. The coordinate is `31890:<group>:<d>` whatever list the entry replies
 * to, so the same `d` accepted into two of the group's lists is one
 * coordinate and the newer event replaces the older, on relays and here
 * alike. An identifier is the entry's rather than the list's, which is the
 * reference client's reading too: its `d` is an external id that names the
 * same film in every list.
 *
 * ### Nothing here has reached a relay
 *
 * The same caveat as the schema, and here it is the one that matters: the
 * queue screen marks a suggestion curated only from a copy read back off the
 * list's relays, so until a member broadcasts an entry from this section the
 * queue still shows it waiting. The broadcast button under each card is how
 * it gets there, or the one at the bottom of the entries screen that sends
 * all of them.
 */
data class GroupCuratedEntry(
    /** The group's statement, whole, with the signature that makes it one. */
    val signedEvent: GroupSignedEvent,
    /** The list the entry is in, as the group last signed its schema. */
    val list: GroupCuratedSchema,
    /** What the statement says, keyed by the list's fields, and where it came from. */
    val entry: CuratedEntry,
) {
    /** The group's nostr identity, which for a derived room is also its id. */
    val publicKey: HexKey get() = signedEvent.publicKey

    /** When the group signed it, which is the order entries are read in. */
    val signedAt: Instant get() = signedEvent.createdAt

    /** The entry's `d`, which with the group's key is its coordinate. */
    val identifier: String get() = entry.identifier

    val title: String get() = entry.title

    /** `31890:<group>:<d>`. Two events sharing one are versions of one entry. */
    fun coordinate(): String = entry.coordinate()

    companion object {
        /**
         * The reading of one signed event against the group's [schemas], or
         * null when it is not one of these.
         *
         * Four ways to be null and they are all the same refusal: the wrong
         * kind, a signature that does not check out as [chatRoomId]'s, a root
         * naming no list this device holds, or an entry the list's own fields
         * refuse. A caller gets an entry the group really signed into a list it
         * really curates, or gets nothing.
         */
        fun of(
            signedEvent: GroupSignedEvent,
            chatRoomId: String,
            schemas: List<GroupCuratedSchema>,
        ): GroupCuratedEntry? {
            if (signedEvent.kind != CuratedEntryEvent.CANONICAL_KIND) return null
            if (!signedEvent.verifies()) return null

            val list = schemas.firstOrNull { it.isRepliedToBy(signedEvent.tags) } ?: return null

            val entry = CuratedEntryEvent.canonical(
                event = signedEvent.toEvent(),
                schema = list.schema,
                coordinate = list.coordinate(),
                curator = list.publicKey,
            ) ?: return null

            return GroupCuratedEntry(signedEvent = signedEvent, list = list, entry = entry)
        }

        /**
         * The newest version of every entry [chatRoomId] signed among
         * [signedEvents], most recently signed first.
         *
         * Newest per coordinate because the kind is addressable and a
         * re-curation is a newer event under the same `d`; newest first so that
         * what the group last decided is at the top, and the id breaking every
         * tie so that two devices reading the same events in different orders
         * show the same thing.
         */
        fun newestPerEntryAmong(
            signedEvents: List<GroupSignedEvent>,
            chatRoomId: String,
            schemas: List<GroupCuratedSchema>,
        ): List<GroupCuratedEntry> =
            signedEvents
                .mapNotNull { of(it, chatRoomId, schemas) }
                .groupBy { it.coordinate() }
                .values
                .map { versions ->
                    versions.maxWith(compareBy({ it.signedAt }, { it.signedEvent.id }))
                }
                .sortedWith(
                    compareByDescending<GroupCuratedEntry> { it.signedAt }
                        .thenByDescending { it.signedEvent.id }
                )
    }
}
