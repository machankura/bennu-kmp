package to.curare.compose.nostr.curated

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kinds 31888 and 31890 as the NIP writes them, read and checked.
 *
 * The fixtures are the NIP's own examples -- the bitcoin.mov schema, *The Rise
 * and Rise of Bitcoin* as `eebb74ab…` suggested it, and the curator signing it
 * off -- because the one property worth pinning is that what that site accepts
 * is accepted here and what it refuses is refused here for the same reason.
 * `CuratedEntryEvent` is a port of the reference module's verifier rule for
 * rule, and this is where each rule gets a case.
 *
 * The other thing pinned is the shape of what comes back: an entry keyed by the
 * schema's field names, with the tags the schema does not define ignored rather
 * than kept. `director`, `i` and `lang` are on the NIP's example and on no
 * field of the NIP's schema, and they are not in the reading.
 */
class CuratedEntryEventTest {
    private val curator = "7c965d8c2acdfd635562da3bcb82596b595be28b008d8b54ec702ed4c67d9d25"
    private val suggester = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"
    private val stranger = "1".repeat(64)

    private val coordinate = "31889:$curator:bitcoin.mov"

    /** The NIP's schema, as `CuratedSchemaEventTest` reads it. */
    private val schema: CuratedSchema = assertNotNull(
        CuratedSchemaEvent.parse(
            Event(
                id = "a".repeat(64),
                pubKey = curator,
                createdAt = 1735689600,
                kind = CuratedSchemaEvent.KIND,
                tags = arrayOf(
                    arrayOf("d", "bitcoin.mov"),
                    arrayOf("title", "bitcoin.mov suggestion"),
                    arrayOf("name", "bitcoin.mov"),
                    arrayOf("description", "Fields for a bitcoin.mov entry."),
                    arrayOf("k", "31888"),
                    arrayOf("visibility", "public"),
                    arrayOf("relay", "wss://ephemeral.mantra.press"),
                    arrayOf("field", "identifier", "token", "required", "", "Identifier", "{\"tag\":\"d\",\"max\":80,\"derived\":true}"),
                    arrayOf("field", "title", "text", "required", "", "Title", "{\"max\":200}"),
                    arrayOf("field", "year", "year", "optional", "", "Year", "{\"min\":1900,\"max\":2100}"),
                    arrayOf("field", "type", "enum", "required", "", "Type", "{\"options\":[\"movie\",\"documentary\",\"short\",\"interview\",\"series\",\"other\"]}"),
                    arrayOf("field", "watchUrl", "url", "optional", "", "Watch / reference URL", "{\"tag\":\"r\",\"marker\":\"watch\",\"max\":500,\"repeat\":true}"),
                    arrayOf("field", "imdbUrl", "url", "optional", "", "IMDb URL", "{\"tag\":\"r\",\"marker\":\"imdb\",\"max\":500}"),
                    arrayOf("field", "image", "image", "optional", "", "Poster image URL (https)", "{\"max\":500}"),
                    arrayOf("field", "hashtags", "token", "optional", "", "Hashtags", "{\"tag\":\"t\",\"max\":60,\"repeat\":true,\"derived\":true}"),
                    arrayOf("field", "description", "longtext", "optional", "", "Description / review", "{\"tag\":\"content\",\"max\":4000}"),
                    arrayOf("require-any", "watchUrl", "imdbUrl"),
                ),
                content = "Fields for a bitcoin.mov entry.",
                sig = "b".repeat(128),
            )
        )
    )

    private val review = "Follows programmer Daniel Mross and the early Bitcoin community from 2011 onward."

    /** *The Rise and Rise of Bitcoin*, as the NIP's example has it. */
    private val riseAndRise: Array<Array<String>> = arrayOf(
        arrayOf("d", "imdb:tt2821314"),
        arrayOf("title", "The Rise and Rise of Bitcoin"),
        arrayOf("year", "2014"),
        arrayOf("type", "documentary"),
        arrayOf("director", "Nicholas Mross"),
        arrayOf("r", "http://bitcoindoc.com/", "watch"),
        arrayOf("r", "https://www.imdb.com/title/tt2821314/", "imdb"),
        arrayOf("image", "https://upload.wikimedia.org/wikipedia/en/9/96/The_Rise_and_Rise_of_Bitcoin_%282014%29_Film_Poster.jpg"),
        arrayOf("i", "imdb:tt2821314"),
        arrayOf("lang", "en"),
        arrayOf("t", "bitcoin"),
        arrayOf("t", "documentary"),
        arrayOf("a", coordinate, "wss://ephemeral.mantra.press", "root"),
        arrayOf("p", curator),
        arrayOf("k", "31889"),
    )

    private val suggestionId = "a809ddc2ad9c7b99e739e872b1b9e99e96ca9a3a4cb28cdf220c74324277eee8"

    @Test
    fun `the NIP's suggestion satisfies the NIP's schema, and reads by field`() {
        assertEquals(emptyList(), CuratedEntryEvent.verifySuggestion(suggestion(), schema, coordinate))

        val entry = assertNotNull(CuratedEntryEvent.suggestion(suggestion(), schema, coordinate))

        assertEquals(CuratedEntryEvent.SUGGESTION_KIND, entry.kind)
        assertEquals(suggester, entry.pubKey)
        assertEquals("imdb:tt2821314", entry.identifier)
        assertEquals("The Rise and Rise of Bitcoin", entry.title)
        assertEquals("31888:$suggester:imdb:tt2821314", entry.coordinate())
        assertNull(entry.source)

        // By the schema's field names, not by tag: the two `r` tags land on
        // two fields, the `t` tags on one, and the content on the field that
        // writes there.
        assertEquals("2014", entry.value("year"))
        assertEquals("documentary", entry.value("type"))
        assertEquals("http://bitcoindoc.com/", entry.value("watchUrl"))
        assertEquals("https://www.imdb.com/title/tt2821314/", entry.value("imdbUrl"))
        assertEquals(listOf("bitcoin", "documentary"), entry.values["hashtags"])
        assertEquals(review, entry.value("description"))

        // What the schema does not define is not read, however much of it there is.
        assertEquals(
            setOf("identifier", "title", "year", "type", "watchUrl", "imdbUrl", "image", "hashtags", "description"),
            entry.values.keys
        )
    }

    @Test
    fun `the minimum suggestion is enough`() {
        // The doc's own minimum: nothing but what the schema insists on, and an
        // IMDb link standing in for a watch link under the require-any rule.
        val minimum = suggestion(
            tags = arrayOf(
                arrayOf("d", "bitcoin-the-end-of-money-as-we-know-it"),
                arrayOf("title", "Bitcoin: The End of Money as We Know It"),
                arrayOf("type", "documentary"),
                arrayOf("r", "https://www.imdb.com/title/tt4654844/", "imdb"),
                arrayOf("t", "bitcoin"),
                arrayOf("a", coordinate, "", "root"),
            ),
            content = ""
        )

        assertEquals(emptyList(), CuratedEntryEvent.verifySuggestion(minimum, schema, coordinate))
        assertNull(assertNotNull(CuratedEntryEvent.suggestion(minimum, schema, coordinate)).value("description"))
    }

    @Test
    fun `the NIP's canonical entry is the list's own, and points at where it came from`() {
        assertEquals(emptyList(), CuratedEntryEvent.verifyCanonical(canonical(), schema, coordinate, curator))

        val entry = assertNotNull(CuratedEntryEvent.canonical(canonical(), schema, coordinate, curator))

        assertTrue(entry.isCanonical)
        assertEquals("imdb:tt2821314", entry.identifier)
        assertEquals("31890:$curator:imdb:tt2821314", entry.coordinate())
        assertEquals(
            CuratedSuggestionRef(
                id = suggestionId,
                coordinate = "31888:$suggester:imdb:tt2821314",
                pubKey = suggester
            ),
            entry.source
        )
    }

    @Test
    fun `a canonical entry nobody suggested has no source`() {
        val unprompted = canonical(tags = riseAndRise)

        assertEquals(emptyList(), CuratedEntryEvent.verifyCanonical(unprompted, schema, coordinate, curator))
        assertNull(assertNotNull(CuratedEntryEvent.canonical(unprompted, schema, coordinate, curator)).source)
    }

    @Test
    fun `curation is not delegated`() {
        // The same event, signed by the suggester: a valid suggestion and not a
        // canonical entry, whatever its kind says.
        val notTheCurator = canonical(pubKey = suggester)

        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_CURATOR, CuratedEntryProblem.Reason.NotTheCurator)),
            CuratedEntryEvent.verifyCanonical(notTheCurator, schema, coordinate, curator)
        )
        assertNull(CuratedEntryEvent.canonical(notTheCurator, schema, coordinate, curator))
    }

    @Test
    fun `a source that is not a suggestion coordinate is refused`() {
        val malformed = canonical(
            tags = riseAndRise + arrayOf(arrayOf("a", "31888:not-a-pubkey:imdb:tt2821314", "", "mention"))
        )

        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_SOURCE, CuratedEntryProblem.Reason.SourceMalformed)),
            CuratedEntryEvent.verifyCanonical(malformed, schema, coordinate, curator)
        )
    }

    @Test
    fun `the wrong kind is not an entry of either sort`() {
        val note = suggestion(kind = 1)

        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_KIND, CuratedEntryProblem.Reason.WrongKind)),
            CuratedEntryEvent.verifySuggestion(note, schema, coordinate)
        )
        // And a suggestion is not a canonical entry: same tags, same author rule aside.
        assertTrue(
            CuratedEntryEvent.verifyCanonical(suggestion(), schema, coordinate, curator)
                .any { it.reason == CuratedEntryProblem.Reason.WrongKind }
        )
    }

    @Test
    fun `a suggestion has to reply to this schema`() {
        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_SCHEMA, CuratedEntryProblem.Reason.NotAReply)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = riseAndRise.without("a")), schema, coordinate)
        )

        // Somebody else's list with the same identifier is somebody else's list.
        val elsewhere = riseAndRise.replacing("a", "31889:$stranger:bitcoin.mov")
        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_SCHEMA, CuratedEntryProblem.Reason.RepliesElsewhere)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = elsewhere), schema, coordinate)
        )
    }

    @Test
    fun `a required field has to be there`() {
        assertEquals(
            listOf(CuratedEntryProblem("title", CuratedEntryProblem.Reason.Missing)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = riseAndRise.without("title")), schema, coordinate)
        )
        assertEquals(
            listOf(CuratedEntryProblem("type", CuratedEntryProblem.Reason.Missing)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = riseAndRise.without("type")), schema, coordinate)
        )
    }

    @Test
    fun `a field that does not repeat appears once`() {
        val twoPosters = riseAndRise + arrayOf(arrayOf("image", "https://example.com/other.jpg"))
        assertEquals(
            listOf(CuratedEntryProblem("image", CuratedEntryProblem.Reason.Repeated)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = twoPosters), schema, coordinate)
        )

        // Where the schema says `repeat`, two is fine -- and both are read.
        val twoWatchLinks = riseAndRise + arrayOf(arrayOf("r", "https://vimeo.com/1", "watch"))
        assertEquals(emptyList(), CuratedEntryEvent.verifySuggestion(suggestion(tags = twoWatchLinks), schema, coordinate))
        assertEquals(
            listOf("http://bitcoindoc.com/", "https://vimeo.com/1"),
            CuratedEntryEvent.read(suggestion(tags = twoWatchLinks), schema).values["watchUrl"]
        )
    }

    @Test
    fun `every value has to match its field`() {
        fun problemsWith(tag: String, value: String) =
            CuratedEntryEvent.verifySuggestion(suggestion(tags = riseAndRise.replacing(tag, value)), schema, coordinate)

        // The things the doc lists as rejected, and why they matter.
        assertEquals(listOf(CuratedEntryProblem("type", CuratedEntryProblem.Reason.NotAnOption)), problemsWith("type", "musical"))
        assertEquals(listOf(CuratedEntryProblem("year", CuratedEntryProblem.Reason.BelowMinimum)), problemsWith("year", "1800"))
        assertEquals(listOf(CuratedEntryProblem("year", CuratedEntryProblem.Reason.AboveMaximum)), problemsWith("year", "2200"))
        assertEquals(listOf(CuratedEntryProblem("year", CuratedEntryProblem.Reason.NotAWholeNumber)), problemsWith("year", "twenty fourteen"))
        assertEquals(listOf(CuratedEntryProblem("image", CuratedEntryProblem.Reason.NotHttps)), problemsWith("image", "http://example.com/poster.jpg"))
        assertEquals(listOf(CuratedEntryProblem("title", CuratedEntryProblem.Reason.TooLong)), problemsWith("title", "x".repeat(201)))
        assertEquals(listOf(CuratedEntryProblem("identifier", CuratedEntryProblem.Reason.NotOneWord)), problemsWith("d", "imdb tt2821314"))

        // A `javascript:` link ends up in an href. Replacing the watch link
        // leaves the IMDb one, so require-any is still met and the URL is the
        // only complaint.
        val scripted = riseAndRise.map {
            if (it[0] == "r" && it[2] == "watch") arrayOf("r", "javascript:alert(1)", "watch") else it
        }.toTypedArray()
        assertEquals(
            listOf(CuratedEntryProblem("watchUrl", CuratedEntryProblem.Reason.NotAUrl)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = scripted), schema, coordinate)
        )
    }

    @Test
    fun `a require-any group needs one of its fields`() {
        val noLinks = riseAndRise.filter { it[0] != "r" }.toTypedArray()

        // Filed under the group's first field, so a form can show it inline.
        assertEquals(
            listOf(CuratedEntryProblem("watchUrl", CuratedEntryProblem.Reason.RequireAnyUnmet)),
            CuratedEntryEvent.verifySuggestion(suggestion(tags = noLinks), schema, coordinate)
        )
    }

    @Test
    fun `a closed list takes suggestions from the curator and the suggesters it names`() {
        val closed = schema.copy(visibility = CuratedVisibility.Closed, suggesters = listOf(suggester))

        assertEquals(emptyList(), CuratedEntryEvent.verifySuggestion(suggestion(pubKey = suggester), closed, coordinate))
        assertEquals(emptyList(), CuratedEntryEvent.verifySuggestion(suggestion(pubKey = curator), closed, coordinate))
        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_VISIBILITY, CuratedEntryProblem.Reason.NotPermitted)),
            CuratedEntryEvent.verifySuggestion(suggestion(pubKey = stranger), closed, coordinate)
        )

        // Private is the same rule; the difference is who is shown the list, not who may write to it.
        val private = closed.copy(visibility = CuratedVisibility.Private)
        assertEquals(
            listOf(CuratedEntryProblem(CuratedEntryEvent.FIELD_VISIBILITY, CuratedEntryProblem.Reason.NotPermitted)),
            CuratedEntryEvent.verifySuggestion(suggestion(pubKey = stranger), private, coordinate)
        )
    }

    @Test
    fun `a value is checked by its type and its config`() {
        val duration = CuratedField("runtime", CuratedFieldType.Duration, isRequired = false, config = CuratedFieldConfig(max = 36000))
        assertEquals(CuratedEntryProblem.Reason.NotPositive, CuratedEntryEvent.checkValue(duration, "0"))
        assertEquals(CuratedEntryProblem.Reason.AboveMaximum, CuratedEntryEvent.checkValue(duration, "36001"))
        // A numeric `max` is a bound, not a length: six digits under the bound is fine.
        assertNull(CuratedEntryEvent.checkValue(duration, "  5400 "))

        val pattern = CuratedField("code", CuratedFieldType.Token, isRequired = false, config = CuratedFieldConfig(pattern = "[a-z]{2,8}"))
        assertNull(CuratedEntryEvent.checkValue(pattern, "abc"))
        // End to end, not anywhere within.
        assertEquals(CuratedEntryProblem.Reason.PatternMismatch, CuratedEntryEvent.checkValue(pattern, "abc1"))

        // A pattern this platform cannot compile is a check it cannot make, and
        // an entry it cannot check is not one it vouches for.
        val broken = pattern.copy(config = CuratedFieldConfig(pattern = "[unclosed"))
        assertEquals(CuratedEntryProblem.Reason.PatternMismatch, CuratedEntryEvent.checkValue(broken, "abc"))

        assertEquals(CuratedEntryProblem.Reason.Empty, CuratedEntryEvent.checkValue(pattern, "   "))

        val strict = CuratedField("site", CuratedFieldType.Url, isRequired = false, config = CuratedFieldConfig(https = true))
        assertEquals(CuratedEntryProblem.Reason.NotHttps, CuratedEntryEvent.checkValue(strict, "http://example.com"))
        assertNull(CuratedEntryEvent.checkValue(strict, "https://example.com"))
    }

    @Test
    fun `a coordinate splits on the first two colons only`() {
        val parsed = assertNotNull(CuratedCoordinate.parse("31888:${suggester.uppercase()}:imdb:tt2821314"))

        assertEquals(31888, parsed.kind)
        assertEquals(suggester, parsed.pubKey, "the pubkey was not lowercased")
        assertEquals("imdb:tt2821314", parsed.identifier, "the identifier's own colon split it")
        assertEquals("31888:$suggester:imdb:tt2821314", parsed.toValue())

        assertNull(CuratedCoordinate.parse("31888:$suggester:"), "an empty identifier was accepted")
        assertNull(CuratedCoordinate.parse("31888:not-a-pubkey:x"))
        assertNull(CuratedCoordinate.parse("naddr1qqxnzd3cxqmrzv3exgmr2wfeqgs"), "a bech32 address is not a coordinate")
    }

    private fun suggestion(
        tags: Array<Array<String>> = riseAndRise,
        content: String = review,
        kind: Int = CuratedEntryEvent.SUGGESTION_KIND,
        pubKey: String = suggester,
    ): Event = Event(
        id = suggestionId,
        pubKey = pubKey,
        createdAt = 1735689660,
        kind = kind,
        tags = tags,
        content = content,
        sig = "b".repeat(128),
    )

    /** The curator signing off on the suggestion, as the NIP's example has it. */
    private fun canonical(
        tags: Array<Array<String>> = riseAndRise + arrayOf(
            arrayOf("a", "31888:$suggester:imdb:tt2821314", "", "mention"),
            arrayOf("e", suggestionId, "", "mention"),
            arrayOf("p", suggester),
        ),
        pubKey: String = curator,
    ): Event = Event(
        id = "c".repeat(64),
        pubKey = pubKey,
        createdAt = 1735689720,
        kind = CuratedEntryEvent.CANONICAL_KIND,
        tags = tags,
        content = review,
        sig = "b".repeat(128),
    )

    private fun Array<Array<String>>.without(tag: String): Array<Array<String>> =
        filter { it[0] != tag }.toTypedArray()

    private fun Array<Array<String>>.replacing(tag: String, value: String): Array<Array<String>> =
        map { if (it[0] == tag) arrayOf(tag, value) + it.drop(2) else it }.toTypedArray()
}
