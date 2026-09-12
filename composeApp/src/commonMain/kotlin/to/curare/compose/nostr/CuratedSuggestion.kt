package to.curare.compose.nostr

import to.curare.compose.database.GENESIS_AT
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.model.intermdiate.LocalNostrEvent
import to.curare.compose.extensions.shortened
import to.curare.compose.nostr.curated.CuratedEntry
import to.curare.compose.nostr.curated.CuratedEntryEvent
import kotlin.time.Instant

/**
 * One suggestion to a list the group curates, as somebody published it and as
 * this device has read it.
 *
 * The schema is the group asking; these are the answers. Anyone may publish one
 * -- that is the point of a list -- so unlike everything else on the group's
 * screen a suggestion is not the group's word and was never signed in the room.
 * It is a `NostrEvent` off a relay, signature-checked on the way in like every
 * other, and then checked against the schema it replies to, because a relay is
 * an untrusted place and an entry that does not satisfy the schema is not shown
 * with the bad parts blanked out. See [CuratedEntryEvent].
 *
 * ### The queue and the list
 *
 * The NIP's words: the canonical entries are the list, and the suggestions are
 * its queue. This is the queue. A suggestion is marked [isCurated] when a
 * canonical entry the *group* signed shares its identifier -- the coordinate
 * both land on -- which is how a reader tells what the group has already taken
 * up from what is still waiting. Only the group's own canonical entries count:
 * curation is the one power a schema does not delegate, and a kind 31890 from
 * anybody else is somebody else's list.
 */
data class CuratedSuggestion(
    /** The entry, keyed by the schema's field names. */
    val entry: CuratedEntry,
    /** The event as it was stored, for the id and the tags: what a canonical entry points back at. */
    val event: NostrEvent,
    /** The suggester's profile where one has arrived, else null. */
    val suggester: Profile?,
    /** Whether a canonical entry by the group shares this suggestion's identifier. */
    val isCurated: Boolean,
) {
    val id: String get() = event.id

    val suggestedAt: Instant get() = event.createdAt

    /**
     * What to call the suggester: their name where a profile has arrived and is
     * a real one, else a short key. A placeholder profile is the one the indexer
     * writes the moment a pubkey is first seen, and it is named "LOADING..." --
     * which is not a name.
     */
    fun suggesterName(): String =
        suggester?.takeUnless { it.createdAt == GENESIS_AT }?.humanReadableNameOrPubkey()
            ?: entry.pubKey.shortened()

    companion object {
        /**
         * The queue of [schema], read off [events], newest first.
         *
         * Two readings over the same rows. Every kind 31888 that satisfies the
         * schema and replies to its coordinate is a suggestion; every kind 31890
         * that does the same *and* is signed by the group is a canonical entry.
         * Both kinds are addressable, so among events sharing a coordinate the
         * newest is the entry and the rest are its history -- the client-side
         * merge that realises an edit, since different relays hold different
         * latest versions. Then the canonical identifiers mark the suggestions.
         *
         * Newest first because that is the order a queue is read in, and the id
         * breaking every tie so two devices reading the same events in different
         * orders show the same thing.
         */
        fun queueOf(schema: GroupCuratedSchema, events: List<LocalNostrEvent>): List<CuratedSuggestion> {
            val coordinate = schema.coordinate()

            val entries = events.mapNotNull { local ->
                val event = local.nostrEvent.toEvent()

                when (event.kind) {
                    CuratedEntryEvent.SUGGESTION_KIND ->
                        CuratedEntryEvent.suggestion(event, schema.schema, coordinate)
                    CuratedEntryEvent.CANONICAL_KIND ->
                        CuratedEntryEvent.canonical(event, schema.schema, coordinate, curator = schema.publicKey)
                    else -> null
                }?.let { entry -> local to entry }
            }
                .groupBy { (_, entry) -> entry.coordinate() }
                .values
                .map { versions ->
                    versions.maxWith(compareBy({ (_, entry) -> entry.createdAt }, { (_, entry) -> entry.id }))
                }

            val curatedIdentifiers = entries
                .filter { (_, entry) -> entry.isCanonical }
                .map { (_, entry) -> entry.identifier }
                .toSet()

            return entries
                .filterNot { (_, entry) -> entry.isCanonical }
                .map { (local, entry) ->
                    CuratedSuggestion(
                        entry = entry,
                        event = local.nostrEvent,
                        suggester = local.profile,
                        isCurated = entry.identifier in curatedIdentifiers,
                    )
                }
                .sortedWith(
                    compareByDescending<CuratedSuggestion> { it.entry.createdAt }
                        .thenByDescending { it.entry.id }
                )
        }
    }
}
