package to.curare.compose.nostr.curated

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * Kinds 31888 and 31890: an entry in a curated list, as somebody suggested it and
 * as the curator accepted it.
 *
 * The two are one shape. A suggestion is a reply to a schema carrying one tag
 * per field the schema defines; a canonical entry is the same tags again, signed
 * by the curator, with an optional pointer back at the suggestion it came from.
 * They are read by one reader and checked by one verifier, and only the kind,
 * the signer and the pointer tell them apart -- which is what lets the list
 * stand on its own if a suggester deletes their entry.
 *
 * This is the other half of [CuratedSchemaEvent]: that is what a list *asks*
 * for, this is what it *gets*. Ported from the reference module's `verifyEntry`
 * rule for rule, so that an entry bitcoin.mov accepts is one this app accepts
 * and an entry it refuses is refused here for the same reason.
 *
 * ### Rejected, not repaired
 *
 * The NIP is explicit: a client MUST verify an entry against its schema and
 * MUST reject one that does not satisfy it, rather than showing it with the
 * offending parts blanked out. Relays carry malformed, partial and hostile
 * events from other applications, and a half-parsed entry is worse than a
 * missing one. So [suggestion] and [canonical] return the entry whole or return
 * null, and [verifySuggestion] and [verifyCanonical] say why.
 */
object CuratedEntryEvent {
    /** The kind anyone publishes a suggestion under. */
    const val SUGGESTION_KIND: Kind = CuratedSchemaEvent.SUGGESTION_KIND

    /** The kind the curator republishes an accepted suggestion as. */
    const val CANONICAL_KIND: Kind = CuratedSchemaEvent.CANONICAL_KIND

    private const val TAG_ADDRESS = "a"
    private const val TAG_EVENT = "e"
    private const val TAG_IDENTIFIER = "d"
    private const val TAG_TITLE = "title"

    /** What every schema coordinate starts with, which is how a root is told from a source. */
    private val SCHEMA_PREFIX = "${CuratedSchemaEvent.KIND}:"

    private val SUGGESTION_PREFIX = "$SUGGESTION_KIND:"

    /**
     * The entry [event] carries, checked against [schema] as a suggestion to the
     * list at [coordinate], or null when it is not one a client should show.
     */
    fun suggestion(event: Event, schema: CuratedSchema, coordinate: String): CuratedEntry? =
        if (verifySuggestion(event, schema, coordinate).isEmpty()) read(event, schema) else null

    /**
     * The entry [event] carries, checked against [schema] as the list's own at
     * [coordinate], or null. [curator] is the pubkey that signed the schema, and
     * the only one this will accept an author of.
     */
    fun canonical(event: Event, schema: CuratedSchema, coordinate: String, curator: HexKey): CuratedEntry? =
        if (verifyCanonical(event, schema, coordinate, curator).isEmpty()) read(event, schema) else null

    /**
     * The entry as the tags say it, whether or not it satisfies the schema.
     *
     * Split from [suggestion] and [canonical] for the same reason
     * [CuratedSchemaEvent.read] is split from its parse: a reader that wants to
     * show *why* -- a test, a form -- verifies separately. Everything here is
     * keyed by the schema's field names rather than by tag, because a field is
     * what a form and a screen know an entry by; the tag is where it was kept.
     */
    fun read(event: Event, schema: CuratedSchema): CuratedEntry {
        val values = schema.fields
            .associate { field -> field.name to valuesOf(field, event.tags, event.content) }
            .filterValues { it.isNotEmpty() }

        return CuratedEntry(
            kind = event.kind,
            id = event.id,
            pubKey = event.pubKey,
            createdAt = event.createdAt,
            identifier = first(event.tags, TAG_IDENTIFIER)?.trim() ?: "",
            title = first(event.tags, TAG_TITLE)?.trim() ?: "",
            values = values,
            source = if (event.kind == CANONICAL_KIND) source(event.tags) else null,
        )
    }

    /**
     * Every reason [event] is not a suggestion to the list at [coordinate], or
     * nothing.
     *
     * The NIP's seven rules in the NIP's order: the kind is the suggestion kind;
     * every required field is present; no field without `repeat` appears twice;
     * every value matches its field's type and config; every `require-any` group
     * is met; the `a` root names the schema; and the author is somebody the
     * schema's visibility lets suggest.
     */
    fun verifySuggestion(event: Event, schema: CuratedSchema, coordinate: String): List<CuratedEntryProblem> =
        verifyEntry(event, schema, coordinate, expectedKind = schema.suggestionKind)

    /**
     * Every reason [event] is not the list's own entry at [coordinate], or nothing.
     *
     * Everything [verifySuggestion] checks, for kind 31890, and two rules on top.
     * The author must be [curator]: curation is the one power a schema does not
     * delegate, and `visibility` has no bearing on it. And a source reference,
     * when there is one, must be a well-formed suggestion coordinate -- a
     * malformed one is worse than none, since it credits the wrong person or
     * points at an entry nobody suggested.
     */
    fun verifyCanonical(
        event: Event,
        schema: CuratedSchema,
        coordinate: String,
        curator: HexKey,
    ): List<CuratedEntryProblem> = buildList {
        addAll(verifyEntry(event, schema, coordinate, expectedKind = CANONICAL_KIND))

        if (event.pubKey != curator) {
            add(CuratedEntryProblem(FIELD_CURATOR, CuratedEntryProblem.Reason.NotTheCurator))
        }

        val source = firstAddress(event.tags, SUGGESTION_PREFIX)
        if (source != null && CuratedCoordinate.parse(source) == null) {
            add(CuratedEntryProblem(FIELD_SOURCE, CuratedEntryProblem.Reason.SourceMalformed))
        }
    }

    /**
     * One value against its field's type and config, or the first thing wrong
     * with it.
     *
     * `max` is a bound on numeric types and a length on everything else, and a
     * numeric field returns before the length check so the two never meet.
     * A pattern the platform cannot compile counts as not matched: the schema
     * asked for a check this reader cannot make, and an entry it cannot check
     * is one it cannot vouch for.
     */
    fun checkValue(field: CuratedField, value: String): CuratedEntryProblem.Reason? {
        val trimmed = value.trim()
        val config = field.config

        if (trimmed.isEmpty()) return CuratedEntryProblem.Reason.Empty

        when (field.type) {
            CuratedFieldType.Text, CuratedFieldType.LongText -> Unit

            CuratedFieldType.Token ->
                if (trimmed.any { it.isWhitespace() }) return CuratedEntryProblem.Reason.NotOneWord

            CuratedFieldType.Url ->
                if (!isSafeUrl(trimmed, httpsOnly = config.https == true)) {
                    return if (config.https == true) {
                        CuratedEntryProblem.Reason.NotHttps
                    } else {
                        CuratedEntryProblem.Reason.NotAUrl
                    }
                }

            CuratedFieldType.Image ->
                if (!isSafeUrl(trimmed, httpsOnly = true)) return CuratedEntryProblem.Reason.NotHttps

            CuratedFieldType.Enum ->
                if (config.options?.contains(trimmed) != true) return CuratedEntryProblem.Reason.NotAnOption

            CuratedFieldType.Year, CuratedFieldType.Duration, CuratedFieldType.Number -> {
                val number = trimmed.toWholeNumberOrNull() ?: return CuratedEntryProblem.Reason.NotAWholeNumber
                if (field.type == CuratedFieldType.Duration && number <= 0) return CuratedEntryProblem.Reason.NotPositive
                config.min?.let { if (number < it) return CuratedEntryProblem.Reason.BelowMinimum }
                config.max?.let { if (number > it) return CuratedEntryProblem.Reason.AboveMaximum }
                // A numeric `max` is a bound, not a length cap.
                return null
            }
        }

        config.max?.let { if (trimmed.length > it) return CuratedEntryProblem.Reason.TooLong }

        config.pattern?.let { pattern ->
            val whole = runCatching { Regex("^(?:$pattern)$") }.getOrNull()
            if (whole == null || !whole.matches(trimmed)) return CuratedEntryProblem.Reason.PatternMismatch
        }

        return null
    }

    /**
     * Every value [field] holds in an event: its tags, or the content body.
     *
     * A field with a marker matches only tags carrying that marker in third
     * position; one without matches every tag of its name, whatever the third
     * element says. Blank values are not values.
     */
    fun valuesOf(field: CuratedField, tags: Array<Array<String>>, content: String): List<String> {
        val tag = field.tag()
        if (tag == CuratedFieldConfig.CONTENT_TAG) {
            return content.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        }

        val marker = field.config.marker
        return tags
            .filter { it.size > 1 && it[0] == tag && it[1].isNotBlank() }
            .filter { marker == null || it.getOrNull(2) == marker }
            .map { it[1] }
    }

    /**
     * Where a canonical entry says it came from, or null for one nobody suggested.
     *
     * The coordinate is the `a` tag with the suggestion kind's prefix, which is
     * what tells it from the schema root; the id is the first `e` tag, pinning
     * the exact version the curator reviewed, and may be absent.
     */
    fun source(tags: Array<Array<String>>): CuratedSuggestionRef? {
        val address = firstAddress(tags, SUGGESTION_PREFIX) ?: return null
        val coordinate = CuratedCoordinate.parse(address) ?: return null

        return CuratedSuggestionRef(
            id = first(tags, TAG_EVENT)?.trim()?.ifEmpty { null },
            coordinate = address,
            pubKey = coordinate.pubKey,
        )
    }

    /** An http or https URL with a host, or with [httpsOnly] an https one. Nothing else reaches a link. */
    fun isSafeUrl(value: String, httpsOnly: Boolean = false): Boolean =
        if (httpsOnly) CuratedSchemaEvent.isHttpsUrl(value) else HTTP_URL.matches(value.trim())

    private val HTTP_URL = Regex("^https?://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE)

    /** The problems a suggestion and a canonical entry share. */
    private fun verifyEntry(
        event: Event,
        schema: CuratedSchema,
        coordinate: String,
        expectedKind: Kind,
    ): List<CuratedEntryProblem> = buildList {
        if (event.kind != expectedKind) {
            add(CuratedEntryProblem(FIELD_KIND, CuratedEntryProblem.Reason.WrongKind))
        }

        // The root is required whenever the schema has a coordinate to reply to,
        // which here is always: a group's schema is signed before anything can
        // reply to it. Only schema coordinates count; an entry carries `a` tags
        // for other reasons too, the source pointer among them.
        val roots = event.tags
            .filter { it.size > 1 && it[0] == TAG_ADDRESS && it[1].startsWith(SCHEMA_PREFIX) }
            .map { it[1] }
        if (roots.isEmpty()) {
            add(CuratedEntryProblem(FIELD_SCHEMA, CuratedEntryProblem.Reason.NotAReply))
        } else if (coordinate !in roots) {
            add(CuratedEntryProblem(FIELD_SCHEMA, CuratedEntryProblem.Reason.RepliesElsewhere))
        }

        for (field in schema.fields) {
            val values = valuesOf(field, event.tags, event.content)

            if (values.isEmpty()) {
                if (field.isRequired) add(CuratedEntryProblem(field.name, CuratedEntryProblem.Reason.Missing))
                continue
            }

            if (values.size > 1 && field.config.repeat != true) {
                add(CuratedEntryProblem(field.name, CuratedEntryProblem.Reason.Repeated))
            }

            // One problem per field is enough.
            values.firstNotNullOfOrNull { checkValue(field, it) }
                ?.let { add(CuratedEntryProblem(field.name, it)) }
        }

        for (group in schema.requireAny) {
            val satisfied = group.any { name ->
                schema.fields.firstOrNull { it.name == name }
                    ?.let { valuesOf(it, event.tags, event.content).isNotEmpty() }
                    ?: false
            }
            if (!satisfied) {
                add(CuratedEntryProblem(group.first(), CuratedEntryProblem.Reason.RequireAnyUnmet))
            }
        }

        if (!schema.canSuggest(event.pubKey, coordinate)) {
            add(CuratedEntryProblem(FIELD_VISIBILITY, CuratedEntryProblem.Reason.NotPermitted))
        }
    }

    /**
     * Whether [pubKey] may suggest to this list, at [coordinate].
     *
     * Everyone on a public list; the curator and the `p` tags on the other two.
     * The curator is read off the coordinate rather than passed, because a
     * [CuratedSchema] does not know who signed it and the coordinate does.
     */
    private fun CuratedSchema.canSuggest(pubKey: HexKey, coordinate: String): Boolean {
        if (visibility == CuratedVisibility.Public) return true
        if (CuratedCoordinate.parse(coordinate)?.pubKey == pubKey) return true
        return pubKey in suggesters
    }

    private fun first(tags: Array<Array<String>>, name: String): String? =
        tags.firstOrNull { it.size > 1 && it[0] == name }?.get(1)

    private fun firstAddress(tags: Array<Array<String>>, prefix: String): String? =
        tags.firstOrNull { it.size > 1 && it[0] == TAG_ADDRESS && it[1].startsWith(prefix) }?.get(1)

    /** An optionally signed run of digits and nothing else, as the reference reads one. */
    private fun String.toWholeNumberOrNull(): Long? =
        takeIf { WHOLE_NUMBER.matches(it) }?.toLongOrNull()

    private val WHOLE_NUMBER = Regex("^-?\\d+$")

    /** The names problems are filed under when they are about the entry rather than a field. */
    const val FIELD_KIND = "kind"
    const val FIELD_SCHEMA = "schema"
    const val FIELD_VISIBILITY = "visibility"
    const val FIELD_CURATOR = "curator"
    const val FIELD_SOURCE = "source"
}

/**
 * One entry in a curated list, read off a kind 31888 or 31890 event.
 *
 * [values] is keyed by the schema's field names, each holding every value the
 * entry carries for that field in tag order -- more than one only for a field
 * the schema lets repeat. A field the entry has nothing for is absent rather
 * than empty. [identifier] and [title] are also in there under whatever the
 * schema calls those fields; they are lifted out because every list has them
 * and every screen wants them.
 */
data class CuratedEntry(
    val kind: Kind,
    val id: HexKey,
    val pubKey: HexKey,
    /** Unix seconds, as the event carries it. */
    val createdAt: Long,
    /** The `d` tag, which with [kind] and [pubKey] is the entry's coordinate. */
    val identifier: String,
    val title: String,
    val values: Map<String, List<String>>,
    /** For a canonical entry, the suggestion it came from, or null when nobody suggested it. */
    val source: CuratedSuggestionRef? = null,
) {
    val isCanonical: Boolean get() = kind == CuratedEntryEvent.CANONICAL_KIND

    /** `kind:pubkey:d`. Two events sharing one are versions of one entry. */
    fun coordinate(): String = CuratedCoordinate(kind, pubKey, identifier).toValue()

    /** The first value of [fieldName], or null when the entry has none. */
    fun value(fieldName: String): String? = values[fieldName]?.firstOrNull()
}

/** Where a canonical entry came from: the suggestion the curator accepted. */
data class CuratedSuggestionRef(
    /** The exact version curated, or null when the curator did not pin one. */
    val id: HexKey?,
    /** `31888:<suggester>:<d>`, which follows the suggester's later edits. */
    val coordinate: String,
    /** The suggester, credited. */
    val pubKey: HexKey,
)

/**
 * One reason an entry does not satisfy its schema: which field, and what about
 * it. [field] is the field's name, or one of the [CuratedEntryEvent] `FIELD_`
 * names for a rule about the entry as a whole; a `require-any` group is filed
 * under its first field, so a form can show it inline.
 */
data class CuratedEntryProblem(val field: String, val reason: Reason) {
    enum class Reason {
        /** Not the kind the schema said suggestions come as, or not the canonical kind. */
        WrongKind,

        /** No `a` root naming any schema. */
        NotAReply,

        /** An `a` root naming some other schema. */
        RepliesElsewhere,

        /** A required field with no value. */
        Missing,

        /** A field without `repeat` with more than one. */
        Repeated,

        Empty,
        NotOneWord,
        NotAUrl,
        NotHttps,
        NotAnOption,
        NotAWholeNumber,
        NotPositive,
        BelowMinimum,
        AboveMaximum,
        TooLong,
        PatternMismatch,

        /** None of a `require-any` group's fields is present. */
        RequireAnyUnmet,

        /** The author may not suggest to a closed or private list. */
        NotPermitted,

        /** A canonical entry signed by somebody other than the schema's author. */
        NotTheCurator,

        /** A source pointer that is not a suggestion coordinate. */
        SourceMalformed,
    }
}

/**
 * An addressable coordinate, `<kind>:<pubkey>:<d>`, split the one way the NIP
 * allows: on the first two colons only. A `d` may itself contain colons --
 * `imdb:tt2821314` does -- so splitting on every one mangles the identifier and
 * miscounts the parts.
 */
data class CuratedCoordinate(val kind: Kind, val pubKey: HexKey, val identifier: String) {
    fun toValue(): String = "$kind:$pubKey:$identifier"

    companion object {
        private val SHAPE = Regex("^(\\d{1,5}):([0-9a-fA-F]{64}):(.+)$")

        /** The three parts, or null for anything that is not a coordinate with a non-empty `d`. */
        fun parse(value: String): CuratedCoordinate? {
            val match = SHAPE.matchEntire(value.trim()) ?: return null
            val (kind, pubKey, identifier) = match.destructured

            return CuratedCoordinate(
                kind = kind.toInt(),
                pubKey = pubKey.lowercase(),
                identifier = identifier,
            )
        }
    }
}
