package to.curare.compose.nostr.curated

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import to.curare.compose.network.serialization.CommonJson

/**
 * The definition of a curated list: what an entry may contain, who may suggest
 * one, and whose list it is.
 *
 * This is the document the other two curated kinds are checked against. A
 * suggestion (kind 31888) is a reply to it that has to satisfy its fields; a
 * canonical entry (kind 31890) is a suggestion its curator republished under
 * their own key. The list is the set of canonical entries and the suggestions
 * are its queue -- and this is the form both are filled in against, published
 * so that another client can render the same form without shipping this code.
 *
 * The curator's pubkey is the namespace. `31889:<pubkey>:<identifier>` names
 * one schema and no other, so nothing global is claimed and nothing can be
 * squatted. Here the curator is a group: the schema is signed by the room's own
 * key, like its profile and its posts, and it is the group -- by quorum -- that
 * accepts a suggestion into the list.
 *
 * The protocol is written up in `bitcoin.mov/docs/NIP.md`, and the shape here
 * follows its reference module tag for tag so that a schema this app signs is
 * one that site's form can be driven by, and one it publishes reads here.
 *
 * ### What is checked, and where
 *
 * [problems] is the list of reasons a schema is unusable, and it is the same
 * list whether the schema was typed into the editor or read off a relay: the
 * NIP's rejection rules, no more. A reader rejects rather than repairs --
 * relays carry malformed and hostile events, and a half-parsed form is worse
 * than a missing one -- and the editor refuses to propose what a reader would
 * refuse to show.
 *
 * [normalized] is the one repair the NIP requires. Every list has a field
 * writing to `d` and one writing to `title`, both required, and a schema that
 * omits or relaxes either gets them put back -- so no schema, wherever it came
 * from, can talk a client into accepting untitled or unaddressable entries.
 */
data class CuratedSchema(
    /** The `d` tag: the schema's identifier, and the last part of its coordinate. */
    val identifier: String,
    /** Labels the event -- "bitcoin.mov suggestion". */
    val title: String,
    /** The list's identity, what people call it -- "bitcoin.mov". */
    val name: String,
    /** What the list is for. Mirrored into the event's content for generic clients. */
    val description: String,
    /**
     * Who may suggest, or null when the schema does not say.
     *
     * Null is never defaulted away: a list that does not say who may suggest to
     * it is not one a client should act on, so an absent or unrecognised
     * visibility is a [CuratedSchemaProblem.VisibilityMissing] rather than a
     * public list by accident. The editor always has one; only a schema read off
     * a relay can be missing it.
     */
    val visibility: CuratedVisibility?,
    /** An https image for the list, or null. */
    val picture: String? = null,
    /**
     * A hostname the list publishes under, or null. Shown in place of [name]
     * wherever the list is named, which is why it is a claim worth being careful
     * with: anyone can put any domain in a tag. See [displayName].
     */
    val domain: String? = null,
    /** The kind suggestions are published under. There is only one. */
    val suggestionKind: Kind = CuratedSchemaEvent.SUGGESTION_KIND,
    val fields: List<CuratedField> = emptyList(),
    /**
     * Groups of field names of which at least one must be present on an entry.
     *
     * How a list says "a watch link or an IMDb link" without making either one
     * required. A group of fewer than two names says nothing and is dropped.
     */
    val requireAny: List<List<String>> = emptyList(),
    /**
     * Pubkeys allowed to suggest on a closed or private list, besides the
     * curator. The `p` tags. Meaningless on a public list, where anyone may.
     */
    val suggesters: List<HexKey> = emptyList(),
    /**
     * Where the list lives: the relays suggestions and canonical entries are
     * published to and read from. Signed into the event so a client that finds
     * the schema anywhere knows where a reply belongs and cannot be sent
     * elsewhere by an unsigned config file.
     */
    val relays: List<String> = emptyList(),
) {
    /**
     * What to call the list on screen: the domain when it has one, else the
     * name. The domain is the stronger identity because it is the one part of
     * this that can be checked against the outside world, NIP-05 style -- and
     * nothing here does check it, so it is a label rather than a badge.
     */
    fun displayName(): String = domain ?: name

    /** Fields a form should prompt for. Derived ones are filled in by the client. */
    fun formFields(): List<CuratedField> = fields.filterNot { it.config.derived == true }

    /**
     * The same schema with the two mandatory fields forced into place.
     *
     * A field writing to `d` and one writing to `title`, both required, in that
     * order at the front when they were missing. A schema that already has them
     * is unchanged apart from the requirement, so running this twice is running
     * it once.
     */
    fun normalized(): CuratedSchema {
        val fields = fields.toMutableList()

        for ((tag, fallback) in CuratedField.MANDATORY) {
            val index = fields.indexOfFirst { it.tag() == tag }
            if (index == -1) {
                fields.add(0, fallback)
            } else if (!fields[index].isRequired) {
                fields[index] = fields[index].copy(isRequired = true)
            }
        }

        return copy(fields = fields)
    }

    /**
     * Every reason this is not a usable schema, or nothing.
     *
     * The NIP's rejection rules and only those: a client MUST refuse a schema
     * missing its identifier, title, name, description or visibility, one whose
     * visibility is not one of the three words, one with no fields, and one
     * whose picture is not https, whose domain is not a hostname, or whose relay
     * is not a relay. The length caps are the reference module's, so a schema
     * this app signs is one that site accepts.
     *
     * Checked on the schema as written, before [normalized] -- "no fields" is
     * about what the publisher said, not about what a reader would put back.
     */
    fun problems(): List<CuratedSchemaProblem> = buildList {
        fun text(value: String, cap: Int, missing: CuratedSchemaProblem, tooLong: CuratedSchemaProblem) {
            if (value.isBlank()) add(missing) else if (value.length > cap) add(tooLong)
        }

        text(identifier, CuratedSchemaEvent.MAX_IDENTIFIER, CuratedSchemaProblem.IdentifierMissing, CuratedSchemaProblem.IdentifierTooLong)
        text(title, CuratedSchemaEvent.MAX_TITLE, CuratedSchemaProblem.TitleMissing, CuratedSchemaProblem.TitleTooLong)
        text(name, CuratedSchemaEvent.MAX_NAME, CuratedSchemaProblem.NameMissing, CuratedSchemaProblem.NameTooLong)
        text(description, CuratedSchemaEvent.MAX_DESCRIPTION, CuratedSchemaProblem.DescriptionMissing, CuratedSchemaProblem.DescriptionTooLong)

        if (visibility == null) add(CuratedSchemaProblem.VisibilityMissing)

        picture?.let {
            if (!CuratedSchemaEvent.isHttpsUrl(it) || it.length > CuratedSchemaEvent.MAX_URL) {
                add(CuratedSchemaProblem.PictureNotHttps)
            }
        }

        domain?.let {
            if (!CuratedSchemaEvent.isDomain(it)) add(CuratedSchemaProblem.DomainInvalid)
        }

        if (fields.isEmpty()) add(CuratedSchemaProblem.NoFields)

        if (relays.any { !CuratedSchemaEvent.isRelayUrl(it) }) add(CuratedSchemaProblem.RelayInvalid)
    }

    fun isUsable(): Boolean = problems().isEmpty()
}

/** A reason a [CuratedSchema] cannot be published or believed. See [CuratedSchema.problems]. */
enum class CuratedSchemaProblem {
    IdentifierMissing,
    IdentifierTooLong,
    TitleMissing,
    TitleTooLong,
    NameMissing,
    NameTooLong,
    DescriptionMissing,
    DescriptionTooLong,
    VisibilityMissing,
    PictureNotHttps,
    DomainInvalid,
    NoFields,
    RelayInvalid,
}

/**
 * Who may suggest to a list, and who it is meant for.
 *
 * Suggesting only. Curation is never delegated: a public list still has exactly
 * one curator, and the `p` tags on a closed list name extra *suggesters*.
 *
 * Relays are open, so [Private] is a convention clients follow rather than an
 * encryption anybody enforces. Nothing secret belongs in a private list.
 */
enum class CuratedVisibility(val value: String) {
    /** Anyone may suggest; anyone may read. */
    Public("public"),

    /** The curator and the `p` tags may suggest; anyone may read. */
    Closed("closed"),

    /** The curator and the `p` tags may suggest; clients should show it only to them. */
    Private("private"),
    ;

    companion object {
        /** Case-insensitive, trimmed, and null for anything but the three words. */
        fun parse(value: String): CuratedVisibility? =
            value.trim().lowercase().let { word -> entries.firstOrNull { it.value == word } }
    }
}

/**
 * What kind of value a field takes. One validation rule each, in the NIP's
 * table; a form picks its input from this.
 *
 * [isNumeric] decides what `max` means: a maximum *value* for these, a maximum
 * *length* for everything else. The double duty is safe because a field is one
 * or the other, never both.
 */
enum class CuratedFieldType(val value: String, val isNumeric: Boolean = false) {
    /** Single-line string, length-capped. */
    Text("text"),

    /** Multi-line string. */
    LongText("longtext"),

    /** One word, no whitespace -- `imdb:tt2821314`. */
    Token("token"),

    /** An http(s) URL; `https` in the config narrows it. */
    Url("url"),

    /** A URL that must be https, since browsers block insecure images. */
    Image("image"),

    /** One of the config's `options`. */
    Enum("enum"),

    /** An integer year, bounded by `min` and `max`. */
    Year("year", isNumeric = true),

    /** A positive integer number of seconds, bounded by `min` and `max`. */
    Duration("duration", isNumeric = true),

    /** An integer, bounded by `min` and `max`. */
    Number("number", isNumeric = true),
    ;

    companion object {
        fun parse(value: String): CuratedFieldType? = entries.firstOrNull { it.value == value }
    }
}

/**
 * Everything about a field beyond its name, type, optionality, placeholder and
 * label -- the JSON object in the seventh position of a `field` tag.
 *
 * Null throughout means "not said", and a key that is not said is not written,
 * so a config with nothing in it is `{}`. [others] carries keys this app does
 * not know, so editing a field another client wrote does not silently strip
 * what that client meant by it.
 */
data class CuratedFieldConfig(
    /** The tag the field writes to. Defaults to the field's name; [CONTENT_TAG] means the body. */
    val tag: String? = null,
    /** A marker written as the tag's third element -- `["r", url, "watch"]`. */
    val marker: String? = null,
    /** Maximum characters for text-ish types, maximum value for numeric ones. */
    val max: Long? = null,
    /** Minimum value. Numeric types only. */
    val min: Long? = null,
    /** The allowed values of an enum. */
    val options: List<String>? = null,
    /** Require https on a url. */
    val https: Boolean? = null,
    /** The tag may appear more than once. */
    val repeat: Boolean? = null,
    /** A regular expression the trimmed value must match end to end. */
    val pattern: String? = null,
    /** The client fills the value in and must not prompt for it. */
    val derived: Boolean? = null,
    /** Helper text under the input. */
    val hint: String? = null,
    /** Keys this app does not know, kept as they came. */
    val others: Map<String, JsonElement> = emptyMap(),
) {
    /** The blob as it goes into the tag. Known keys first, in the NIP's order, then [others]. */
    fun toJson(): String = buildJsonObject {
        tag?.let { put(KEY_TAG, it) }
        marker?.let { put(KEY_MARKER, it) }
        max?.let { put(KEY_MAX, it) }
        min?.let { put(KEY_MIN, it) }
        options?.let { put(KEY_OPTIONS, JsonArray(it.map(::JsonPrimitive))) }
        https?.let { put(KEY_HTTPS, it) }
        repeat?.let { put(KEY_REPEAT, it) }
        pattern?.let { put(KEY_PATTERN, it) }
        derived?.let { put(KEY_DERIVED, it) }
        hint?.let { put(KEY_HINT, it) }
        others.forEach { (key, value) -> put(key, value) }
    }.toString()

    companion object {
        /** The `tag` value meaning the event's content rather than a tag. */
        const val CONTENT_TAG = "content"

        private const val KEY_TAG = "tag"
        private const val KEY_MARKER = "marker"
        private const val KEY_MAX = "max"
        private const val KEY_MIN = "min"
        private const val KEY_OPTIONS = "options"
        private const val KEY_HTTPS = "https"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_DERIVED = "derived"
        private const val KEY_HINT = "hint"

        private val KNOWN_KEYS = setOf(
            KEY_TAG, KEY_MARKER, KEY_MAX, KEY_MIN, KEY_OPTIONS,
            KEY_HTTPS, KEY_REPEAT, KEY_PATTERN, KEY_DERIVED, KEY_HINT,
        )

        /**
         * The blob read back, leniently.
         *
         * A malformed blob costs the field its constraints and not its life: the
         * NIP says a bad config must not invalidate the field, so anything that
         * is not a JSON object reads as no config at all, and a known key of the
         * wrong type is dropped rather than kept as something it is not.
         */
        fun fromJson(json: String?): CuratedFieldConfig {
            if (json.isNullOrBlank()) return CuratedFieldConfig()

            val obj = runCatching { CommonJson.parseToJsonElement(json) }.getOrNull() as? JsonObject
                ?: return CuratedFieldConfig()

            fun string(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

            fun long(key: String): Long? =
                (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

            fun boolean(key: String): Boolean? =
                (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

            return CuratedFieldConfig(
                tag = string(KEY_TAG),
                marker = string(KEY_MARKER),
                max = long(KEY_MAX),
                min = long(KEY_MIN),
                options = (obj[KEY_OPTIONS] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull },
                https = boolean(KEY_HTTPS),
                repeat = boolean(KEY_REPEAT),
                pattern = string(KEY_PATTERN),
                derived = boolean(KEY_DERIVED),
                hint = string(KEY_HINT),
                others = obj.filterKeys { it !in KNOWN_KEYS },
            )
        }
    }
}

/**
 * One field an entry may -- or must -- carry.
 *
 * ```
 * ["field", name, type, "required" | "optional", placeholder, label, config]
 * ```
 *
 * The four things a form needs are positional and everything else is the JSON
 * blob in the last position, which is [CuratedFieldConfig].
 */
data class CuratedField(
    /** Stable name; the key a form collects the value under. */
    val name: String,
    val type: CuratedFieldType,
    val isRequired: Boolean,
    /** An example value shown in an empty input. May be empty. */
    val placeholder: String = "",
    /** The human label for the input. Empty means [name]. */
    val label: String = "",
    val config: CuratedFieldConfig = CuratedFieldConfig(),
) {
    /** The tag this field writes to; [CuratedFieldConfig.CONTENT_TAG] means the body. */
    fun tag(): String = config.tag ?: name

    fun labelOrName(): String = label.ifBlank { name }

    fun toTagArray(): Array<String> = arrayOf(
        TAG_NAME,
        name,
        type.value,
        if (isRequired) REQUIRED else OPTIONAL,
        placeholder,
        label,
        config.toJson(),
    )

    companion object {
        const val TAG_NAME = "field"

        private const val REQUIRED = "required"
        private const val OPTIONAL = "optional"

        /**
         * The tag read back, or null for one that is not a field.
         *
         * Null for a blank name or an unknown type, which are the two things a
         * form cannot do anything with. Everything after them is optional: a
         * missing placeholder is empty, a missing label falls back to the name,
         * and a missing or malformed config is no config -- see
         * [CuratedFieldConfig.fromJson].
         */
        fun parse(tag: Array<String>): CuratedField? {
            if (tag.size < 3 || tag[0] != TAG_NAME) return null

            val name = tag[1].trim()
            if (name.isEmpty()) return null

            val type = CuratedFieldType.parse(tag[2]) ?: return null

            return CuratedField(
                name = name,
                type = type,
                isRequired = tag.getOrNull(3) == REQUIRED,
                placeholder = tag.getOrNull(4) ?: "",
                label = tag.getOrNull(5) ?: "",
                config = CuratedFieldConfig.fromJson(tag.getOrNull(6)),
            )
        }

        /** The `title` field a schema gets when it left its own out. */
        val TITLE = CuratedField(
            name = "title",
            type = CuratedFieldType.Text,
            isRequired = true,
            label = "Title",
            config = CuratedFieldConfig(max = 200),
        )

        /**
         * The `d` field a schema gets when it left its own out. Derived, so a
         * form never asks for it: the client works an identifier out of the
         * entry itself.
         */
        val IDENTIFIER = CuratedField(
            name = "identifier",
            type = CuratedFieldType.Token,
            isRequired = true,
            label = "Identifier",
            config = CuratedFieldConfig(tag = "d", max = 80, derived = true),
        )

        /**
         * The two fields every list has, keyed by the tag each writes to and in
         * the order [CuratedSchema.normalized] puts them back -- title first, so
         * that `d` ends up in front of it.
         */
        val MANDATORY: List<Pair<String, CuratedField>> = listOf(
            "title" to TITLE,
            "d" to IDENTIFIER,
        )
    }
}
