package to.curare.compose.nostr.curated

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.time.Clock

/**
 * Kind 31889: a curated schema event, and the two kinds it governs.
 *
 * ```
 *  31889  curated schema      the definition of a list, by its curator
 *  31888  curated suggestion  an entry anyone proposes, in reply to the schema
 *  31890  curated canonical   a suggestion the curator republished as the list's own
 * ```
 *
 * All three are addressable, so `kind:pubkey:d` names one entry and republishing
 * with the same `d` replaces it. That is what makes a schema revisable: editing
 * one signs a new event under the same coordinate, and every suggestion that
 * replied to the coordinate -- rather than to an event id -- stays attached.
 *
 * This object is the wire shape: tags in, tags out. [CuratedSchema] is what the
 * tags mean, and `GroupCuratedSchema` is a group's own signed one.
 */
object CuratedSchemaEvent {
    const val KIND: Kind = 31889

    /** The kind a schema's suggestions are published under -- its `k` tag. */
    const val SUGGESTION_KIND: Kind = 31888

    /** The kind the curator republishes an accepted suggestion as. */
    const val CANONICAL_KIND: Kind = 31890

    /** Length caps on the identity tags, the reference module's. */
    const val MAX_IDENTIFIER = 100
    const val MAX_TITLE = 200
    const val MAX_NAME = 100
    const val MAX_DESCRIPTION = 500
    const val MAX_URL = 500
    const val MAX_DOMAIN = 253

    private const val TAG_IDENTIFIER = "d"
    private const val TAG_TITLE = "title"
    private const val TAG_NAME = "name"
    private const val TAG_DESCRIPTION = "description"
    private const val TAG_KIND = "k"
    private const val TAG_VISIBILITY = "visibility"
    private const val TAG_PICTURE = "picture"
    private const val TAG_DOMAIN = "domain"
    private const val TAG_REQUIRE_ANY = "require-any"
    private const val TAG_SUGGESTER = "p"
    private const val TAG_RELAY = "relay"

    /** The addressable coordinate: what a suggestion's `a` root names. */
    fun coordinate(pubkey: HexKey, identifier: String): String = "$KIND:$pubkey:$identifier"

    /**
     * The schema an event declares, or null when the event is not a usable one.
     *
     * Defensive in the way a relay demands. The wrong kind is null. Any of
     * [CuratedSchema.problems] is null -- a schema that would be rejected on
     * publish is rejected on read, and for the same reasons. A `field` tag that
     * will not parse is dropped and the rest are kept, which is the one place
     * the NIP asks for tolerance -- except that one of a type this app does not
     * know is kept aside rather than lost, see [CuratedUnsupportedField]; a
     * `require-any` naming fewer than two fields says nothing and is dropped.
     *
     * What comes back is [CuratedSchema.normalized]: the two mandatory fields
     * forced in, whatever the event said.
     */
    fun parse(event: Event): CuratedSchema? {
        if (event.kind != KIND) return null

        return read(event.tags, event.content).takeIf { it.isUsable() }?.normalized()
    }

    /**
     * The tags as a schema, whether or not they make a usable one.
     *
     * Split from [parse] so that a reader wanting the reasons -- the editor,
     * a test -- can ask [CuratedSchema.problems] of the result. The identity
     * values are trimmed, and the description falls back to the content the way
     * generic clients read it: the tag is authoritative and the content is the
     * mirror.
     */
    fun read(tags: Array<Array<String>>, content: String): CuratedSchema {
        fun first(name: String): String? =
            tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

        fun all(name: String): List<String> =
            tags.filter { it.size > 1 && it[0] == name }.map { it[1] }

        return CuratedSchema(
            identifier = first(TAG_IDENTIFIER)?.trim() ?: "",
            title = first(TAG_TITLE)?.trim() ?: "",
            name = first(TAG_NAME)?.trim() ?: "",
            description = (first(TAG_DESCRIPTION)?.trim()?.ifEmpty { null } ?: content).trim(),
            // Null for an absent or unrecognised word, and null stays null: see
            // the field's own note on why it is never defaulted.
            visibility = first(TAG_VISIBILITY)?.let(CuratedVisibility::parse),
            picture = first(TAG_PICTURE)?.trim()?.ifEmpty { null },
            domain = first(TAG_DOMAIN)?.trim()?.ifEmpty { null }?.let(::normalizeDomain),
            suggestionKind = first(TAG_KIND)?.trim()?.toIntOrNull() ?: SUGGESTION_KIND,
            fields = tags.mapNotNull(CuratedField::parse),
            unsupportedFields = tags.mapNotNull(CuratedField::parseUnsupported),
            requireAny = tags
                .filter { it.isNotEmpty() && it[0] == TAG_REQUIRE_ANY }
                .map { group -> group.drop(1).map { it.trim() }.filter { it.isNotEmpty() } }
                .filter { it.size > 1 },
            // Only what is a pubkey. A `p` tag holding anything else names nobody,
            // and keeping it would have the editor render it as an npub it is not.
            suggesters = all(TAG_SUGGESTER).map { it.trim().lowercase() }.filter(::isPubkey).distinct(),
            relays = all(TAG_RELAY).map { it.trim() }.distinct(),
        )
    }

    /**
     * The unsigned event that publishes [schema], for the group to sign.
     *
     * Built from the schema alone rather than from the group's last event, the
     * way a relay list is and for the same reason: a schema is one document
     * whose whole point in being re-signed is to replace the last one, and
     * carrying an old tag forward would make removing a field impossible.
     *
     * The content mirrors the description so that a client reading content
     * rather than tags still shows something; the tag is the authoritative one.
     *
     * A field of a type this app does not know goes out as it came in, after
     * the fields it does: the editor cannot show it, and a schema re-signed
     * without it would have changed the list's rules behind the member's back.
     */
    fun template(
        schema: CuratedSchema,
        createdAt: Long = Clock.System.now().epochSeconds,
    ): EventTemplate<Nothing> {
        val normalized = schema.normalized()

        val tags = buildList {
            add(arrayOf(TAG_IDENTIFIER, normalized.identifier))
            add(arrayOf(TAG_TITLE, normalized.title))
            add(arrayOf(TAG_NAME, normalized.name))
            add(arrayOf(TAG_DESCRIPTION, normalized.description))
            add(arrayOf(TAG_KIND, normalized.suggestionKind.toString()))
            // Absent rather than invented when the schema has none. Every reader,
            // this one included, then refuses the event -- which is the right
            // outcome for a schema that does not say who may suggest to it, and
            // one the editor never produces.
            normalized.visibility?.let { add(arrayOf(TAG_VISIBILITY, it.value)) }

            normalized.picture?.let { add(arrayOf(TAG_PICTURE, it)) }
            normalized.domain?.let { add(arrayOf(TAG_DOMAIN, normalizeDomain(it))) }

            normalized.fields.forEach { add(it.toTagArray()) }
            normalized.unsupportedFields.forEach { add(it.tag.toTypedArray()) }
            normalized.requireAny.forEach { group -> add(arrayOf(TAG_REQUIRE_ANY, *group.toTypedArray())) }
            normalized.suggesters.forEach { add(arrayOf(TAG_SUGGESTER, it)) }
            normalized.relays.forEach { add(arrayOf(TAG_RELAY, it)) }
        }

        return EventTemplate(
            createdAt = createdAt,
            kind = KIND,
            tags = tags.toTypedArray(),
            content = normalized.description,
        )
    }

    /**
     * A hostname and nothing else: at least two labels, each of letters, digits
     * and inner hyphens, no scheme, port or path, 253 characters at most. What
     * [normalizeDomain] leaves of a pasted URL is checked against this, so
     * `https://Bitcoin.MOV/` is accepted and stored as `bitcoin.mov`.
     */
    private val DOMAIN = Regex(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$"
    )

    /** Trim the things people paste around a bare hostname. */
    fun normalizeDomain(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("^[a-z][a-z0-9+.-]*://"), "")
        .removePrefix("@")
        .replace(Regex("/.*$"), "")
        .replace(Regex(":\\d+$"), "")
        .removeSuffix(".")

    /** 64 lowercase hex characters: an x-only pubkey as nostr writes one. */
    fun isPubkey(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun isDomain(value: String): Boolean =
        normalizeDomain(value).let { it.length <= MAX_DOMAIN && DOMAIN.matches(it) }

    /**
     * An https URL with a host: the only kind of picture a schema may carry,
     * because it gets rendered and browsers block the rest on a secure page.
     */
    fun isHttpsUrl(value: String): Boolean = HTTPS_URL.matches(value.trim())

    private val HTTPS_URL = Regex("^https://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE)

    /**
     * A `ws://` or `wss://` URL with a host, which is what the NIP accepts in a
     * `relay` tag. The editor is stricter about what it will let a group sign --
     * see `GroupRelaySet.relayUrlOrNull` -- but a schema read off a relay is held
     * to the NIP's rule and not to this app's.
     */
    fun isRelayUrl(value: String): Boolean = RELAY_URL.matches(value.trim())

    private val RELAY_URL = Regex("^wss?://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE)
}
