package to.curare.compose.nostr

import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.types.SynchronizationFilter
import to.curare.compose.nostr.curated.CuratedSchema
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Instant

/**
 * A curated schema the group signed: a list it curates, as the group defined it.
 *
 * Kind 31889 -- see [CuratedSchemaEvent] -- authored by the room's own key. That
 * makes the group the curator of the list the schema describes: anyone may reply
 * to it with a suggestion, and only an event signed by the same key counts as the
 * list itself. For a group that means the same thing everything else on its screen
 * means: a quorum decides what goes in.
 *
 * ### Several per group, one per identifier
 *
 * Unlike a profile, of which a group has one, a group may curate several lists,
 * and each is its own coordinate `31889:<room>:<d>`. So the reading is per
 * identifier: among the events sharing a `d` the newest wins, the way it does for
 * a profile, because the kind is addressable and re-signing one is how a schema
 * is edited. Two different identifiers are two lists and both are shown.
 *
 * Read off [GroupSignedEvent] and gated on [GroupSignedEvent.verifies], for the
 * reasons `GroupNostrProfile` gives at length: a group-signed event is not a
 * `NostrEvent`, and only an event the room itself signed is the group speaking.
 *
 * ### Nothing here has reached a relay
 *
 * The same caveat as the profile, the relay lists and the posts. A schema is the
 * one statement here that exists to be *replied to* by strangers, so until it is
 * published the list it defines has no queue -- the relays it names say where
 * suggestions would go, and nothing yet puts the schema there for them to find.
 * The queue is read all the same, by `CuratedSuggestion.queueOf` over whatever
 * those relays hold for [coordinate], so a schema that reached a relay by some
 * other road has its suggestions shown here.
 */
data class GroupCuratedSchema(
    /** The group's statement, whole, with the signature that makes it one. */
    val signedEvent: GroupSignedEvent,
    /** What the statement says, already checked and normalized. */
    val schema: CuratedSchema,
) {
    /** The group's nostr identity, which for a derived room is also its id. */
    val publicKey: HexKey get() = signedEvent.publicKey

    /** When the group signed it, which is what decides the newest of two. */
    val signedAt: Instant get() = signedEvent.createdAt

    /** The schema's `d`, and the last part of its coordinate. */
    val identifier: String get() = schema.identifier

    /** What a suggestion's `a` root names to reply to this list. */
    fun coordinate(): String = CuratedSchemaEvent.coordinate(publicKey, identifier)

    /**
     * What to ask a relay for to read this list's queue: the NIP's two queries,
     * as one request.
     *
     * Everything suggested to the list is whatever anyone published in reply
     * to the coordinate; the list itself is what the *group* published in reply
     * to it. The `authors` on the second is redundant with the rule that only
     * the curator's canonical entries count -- `CuratedSuggestion.queueOf`
     * enforces that on the way in regardless -- and is there to spare the relay
     * the work. Both land in the same table, and [queueReadFilter] is how they
     * are read back.
     */
    fun queueFilters(): Array<SynchronizationFilter> = arrayOf(
        SynchronizationFilter(
            kinds = arrayOf(schema.suggestionKind),
            tags = mapOf(COORDINATE_TAG to listOf(coordinate())),
            limit = QUEUE_LIMIT,
        ),
        SynchronizationFilter(
            kinds = arrayOf(CuratedSchemaEvent.CANONICAL_KIND),
            authors = arrayOf(publicKey),
            tags = mapOf(COORDINATE_TAG to listOf(coordinate())),
            limit = QUEUE_LIMIT,
        ),
    )

    /**
     * What to watch locally for the same rows: both kinds, replying to the
     * coordinate. One filter rather than two because the local store is a
     * single table and the reading tells the kinds apart itself.
     */
    fun queueReadFilter(): SynchronizationFilter = SynchronizationFilter(
        kinds = arrayOf(schema.suggestionKind, CuratedSchemaEvent.CANONICAL_KIND),
        tags = mapOf(COORDINATE_TAG to listOf(coordinate())),
    )

    companion object {
        /** The tag a reply carries its schema's coordinate in. */
        private const val COORDINATE_TAG = "a"

        /**
         * How much of a queue one request asks for: the reference client's
         * window, and more than any list here is likely to have. NIP-01 scopes
         * a limit to the stored events a relay sends before EOSE, so a longer
         * queue is read on the next visit rather than never.
         */
        private const val QUEUE_LIMIT = 500

        /**
         * The reading of one signed event, or null when it is not one of these.
         *
         * Three ways to be null and they are all the same refusal: the wrong
         * kind, a signature that does not check out as [chatRoomId]'s, or tags
         * that do not make a usable schema. A caller gets a schema the group
         * really signed and a client could really act on, or gets nothing.
         */
        fun of(signedEvent: GroupSignedEvent, chatRoomId: String): GroupCuratedSchema? {
            if (signedEvent.kind != CuratedSchemaEvent.KIND) return null
            if (!signedEvent.verifies()) return null

            val schema = CuratedSchemaEvent.parse(signedEvent.toEvent()) ?: return null

            return GroupCuratedSchema(signedEvent = signedEvent, schema = schema)
        }

        /**
         * The newest schema per identifier [chatRoomId] signed among
         * [signedEvents], most recently signed list first.
         *
         * Newest per identifier because the kind is addressable and an edit is a
         * newer event under the same `d`; newest list first so that an edit
         * surfaces, and the id breaking every tie so that two devices reading the
         * same events in different orders show the same thing.
         */
        fun newestPerListAmong(
            signedEvents: List<GroupSignedEvent>,
            chatRoomId: String,
        ): List<GroupCuratedSchema> =
            signedEvents
                .mapNotNull { of(it, chatRoomId) }
                .groupBy { it.identifier }
                .values
                .map { versions ->
                    versions.maxWith(compareBy({ it.signedAt }, { it.signedEvent.id }))
                }
                .sortedWith(
                    compareByDescending<GroupCuratedSchema> { it.signedAt }
                        .thenByDescending { it.signedEvent.id }
                )

        /**
         * The event to ask the group to sign for [schema].
         *
         * Built from the schema alone -- see [CuratedSchemaEvent.template] for
         * why an edit does not carry the last event's tags forward.
         */
        fun template(schema: CuratedSchema): EventTemplate<*> = CuratedSchemaEvent.template(schema)
    }
}
