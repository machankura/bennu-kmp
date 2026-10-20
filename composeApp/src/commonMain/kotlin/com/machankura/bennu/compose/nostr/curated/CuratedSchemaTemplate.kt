package com.machankura.bennu.compose.nostr.curated

/**
 * How a client works an entry's `d` out of the entry itself.
 *
 * The NIP marks the identifier field `derived` and leaves the derivation to the
 * publishing client, which is fine for a site with one list and one rule --
 * bitcoin.mov's -- and useless for a list started from a template, whose
 * entries will be composed by clients that have never seen its code. So
 * curare.to records the rule *in the schema*: a machine-readable mark,
 * `[rule:<key>]`, at the end of a human sentence in the identifier field's
 * hint. A client that knows the rule applies it and one that does not can at
 * least read what it is; one that finds no mark falls back to [Title].
 *
 * This app composes no entries and applies none of these. It writes the mark
 * so that a list the group starts here is one that site's form can post to
 * with the right identifier -- the same link one post whoever submits it,
 * say -- rather than with whatever its fallback happens to be.
 *
 * The keys, the wording and the shape of the hint are `lib/protocol/derive.ts`
 * in the curare.to repository, byte for byte, so that a template started here
 * and one started there produce the same schema.
 */
enum class CuratedDeriveRule(
    /** The word in the mark. */
    val key: String,
    /** The rule as the hint says it, after "Derived by the client — ". */
    private val phrase: String,
) {
    /**
     * A post with a link is identified by the link, so the same link is one
     * post whoever submits it; a text post by its title and its author.
     */
    LinkOrTitle("link-or-title", "the link, else the title and author"),

    /** Two people's same-titled posts are two posts; one person's is an edit. */
    TitleAuthor("title-author", "the title and author"),

    /**
     * A catalogue entry with an external id (`imdb:tt…`) is one entry however
     * often it is suggested; otherwise the title and year.
     */
    ExternalOrTitleYear("external-or-title-year", "the external id, else the title and year"),

    /** The same title is the same post, whoever posts it. */
    Title("title", "the title"),
    ;

    /** The hint that records this rule on an identifier field. */
    fun hint(): String = "Derived by the client — $phrase. [rule:$key]"
}

/**
 * The shapes a list can start from: curare.to's templates, the ones its
 * "Start a list" page offers, so that a list started in this app and one
 * started on that site are the same kind of thing.
 *
 * Every template carries the two fields every list has -- the identifier,
 * derived and with its [rule] recorded in its hint, and the title -- and
 * declares no derived `t` field: the hashtag rule is bitcoin.mov's own, and a
 * list is its own topic. Flair is the sub-topic.
 *
 * The field names, labels, placeholders and configs are signed into the
 * schema and read by other clients, which is why they are plain strings here
 * rather than resources: they are protocol data, not this app's copy. What
 * the picker *calls* a template is UI text and lives in the catalogue.
 *
 * The fields are `lib/protocol/templates.ts` in the curare.to repository,
 * field for field. A template added there belongs here too.
 */
enum class CuratedSchemaTemplate(
    /** The stable key, the one curare.to's editor takes as `?template=`. */
    val key: String,
    val rule: CuratedDeriveRule,
    val fields: List<CuratedField>,
    val requireAny: List<List<String>> = emptyList(),
) {
    /** Reddit's default: a title with a link or some text, a picture, and a flair. */
    Links(
        key = "links",
        rule = CuratedDeriveRule.LinkOrTitle,
        fields = listOf(
            identifier(CuratedDeriveRule.LinkOrTitle),
            title(placeholder = "What is it?"),
            CuratedField(
                name = "link",
                type = CuratedFieldType.Url,
                isRequired = false,
                placeholder = "https://…",
                label = "Link",
                config = CuratedFieldConfig(tag = "r", marker = "link", max = 500),
            ),
            CuratedField(
                name = "body",
                type = CuratedFieldType.LongText,
                isRequired = false,
                placeholder = "Say more, or nothing.",
                label = "Text",
                config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG, max = 10_000),
            ),
            CuratedField(
                name = "image",
                type = CuratedFieldType.Image,
                isRequired = false,
                placeholder = "https://…/picture.jpg",
                label = "Picture",
                config = CuratedFieldConfig(max = 500, hint = "An https link to an image."),
            ),
            flair("discussion", "news", "question"),
        ),
        requireAny = listOf(listOf("link", "body")),
    ),

    /** Text posts only: a title and a body, with a flair. */
    Discussion(
        key = "discussion",
        rule = CuratedDeriveRule.TitleAuthor,
        fields = listOf(
            identifier(CuratedDeriveRule.TitleAuthor),
            title(placeholder = "What do you want to talk about?"),
            CuratedField(
                name = "body",
                type = CuratedFieldType.LongText,
                isRequired = true,
                label = "Text",
                config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG, max = 10_000),
            ),
            flair("question", "meta"),
        ),
    ),

    /**
     * bitcoin.mov's shape, generalised: things with a year, a type, an
     * external id and links, one entry per thing however often it is suggested.
     */
    Catalogue(
        key = "catalogue",
        rule = CuratedDeriveRule.ExternalOrTitleYear,
        fields = listOf(
            identifier(CuratedDeriveRule.ExternalOrTitleYear),
            title(placeholder = "The thing’s name"),
            CuratedField(
                name = "year",
                type = CuratedFieldType.Year,
                isRequired = false,
                placeholder = "2014",
                label = "Year",
                config = CuratedFieldConfig(min = 1900, max = 2100),
            ),
            CuratedField(
                name = "type",
                type = CuratedFieldType.Enum,
                isRequired = true,
                label = "Type",
                config = CuratedFieldConfig(
                    options = listOf("movie", "documentary", "short", "series", "book", "other"),
                ),
            ),
            CuratedField(
                name = "externalId",
                type = CuratedFieldType.Token,
                isRequired = false,
                placeholder = "imdb:tt2821314",
                label = "External id",
                config = CuratedFieldConfig(
                    tag = "i",
                    max = 60,
                    hint = "Helps dedupe, e.g. imdb:tt2821314 or isbn:…",
                ),
            ),
            CuratedField(
                name = "watchUrl",
                type = CuratedFieldType.Url,
                isRequired = false,
                placeholder = "https://…",
                label = "Where to see it",
                config = CuratedFieldConfig(tag = "r", marker = "watch", max = 500, repeat = true),
            ),
            CuratedField(
                name = "referenceUrl",
                type = CuratedFieldType.Url,
                isRequired = false,
                placeholder = "https://…",
                label = "Reference",
                config = CuratedFieldConfig(tag = "r", marker = "reference", max = 500),
            ),
            CuratedField(
                name = "image",
                type = CuratedFieldType.Image,
                isRequired = false,
                placeholder = "https://…/poster.jpg",
                label = "Picture",
                config = CuratedFieldConfig(max = 500),
            ),
            CuratedField(
                name = "lang",
                type = CuratedFieldType.Token,
                isRequired = false,
                placeholder = "en",
                label = "Language",
                config = CuratedFieldConfig(max = 60, pattern = "[A-Za-z]{2,8}([-_][A-Za-z0-9]{2,8})*"),
            ),
            CuratedField(
                name = "description",
                type = CuratedFieldType.LongText,
                isRequired = false,
                placeholder = "Why is it worth a look?",
                label = "Description",
                config = CuratedFieldConfig(tag = CuratedFieldConfig.CONTENT_TAG, max = 4000),
            ),
        ),
        requireAny = listOf(listOf("watchUrl", "referenceUrl")),
    ),

    /** Just the two fields every list has, and whatever the group adds. */
    Custom(
        key = "custom",
        rule = CuratedDeriveRule.Title,
        fields = listOf(identifier(CuratedDeriveRule.Title), title(placeholder = "")),
    ),
    ;

    companion object {
        /**
         * The template with [key], or [Custom] for null and for a key this build
         * does not know.
         *
         * Custom rather than the first, which is what curare.to falls back to,
         * because Custom is the two fields every list has and nothing else: the
         * one shape that cannot presume anything about a list it was not asked
         * for. Null is what an edit carries, where the template is meaningless,
         * and what a screen handed a loaded state without one gets.
         */
        fun byKey(key: String?): CuratedSchemaTemplate = entries.firstOrNull { it.key == key } ?: Custom
    }
}

// The fields the templates share, as functions of the file rather than of the
// companion: an enum's entries are built before its companion is, so they
// cannot call into it.

/**
 * The identifier field every template starts with: [CuratedField.IDENTIFIER]
 * with the rule recorded in its hint.
 */
private fun identifier(rule: CuratedDeriveRule): CuratedField =
    CuratedField.IDENTIFIER.copy(
        config = CuratedField.IDENTIFIER.config.copy(hint = rule.hint()),
    )

/** The title field every template starts with: [CuratedField.TITLE] with an example. */
private fun title(placeholder: String): CuratedField =
    CuratedField.TITLE.copy(placeholder = placeholder)

/** A sub-topic an entry may pick one of. */
private fun flair(vararg options: String): CuratedField = CuratedField(
    name = "flair",
    type = CuratedFieldType.Enum,
    isRequired = false,
    label = "Flair",
    config = CuratedFieldConfig(options = options.toList()),
)
