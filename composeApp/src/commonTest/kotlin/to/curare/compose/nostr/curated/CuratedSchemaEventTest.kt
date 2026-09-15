package to.curare.compose.nostr.curated

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kind 31889 as the NIP writes it, read and written back.
 *
 * The fixture is the bitcoin.mov schema from the NIP's own example, tag for tag,
 * because the point of publishing a schema is that another client can drive its
 * form from it -- so the one thing worth pinning is that what that site published
 * reads here, and that what this app signs would read there.
 *
 * The other half is the rejection list. A schema is what suggestions are checked
 * against, and a reader that repaired a broken one would be accepting entries
 * against a form nobody signed. Every rule the NIP says a client MUST refuse on
 * has a case here, and so does the one repair it says a client MUST make.
 */
class CuratedSchemaEventTest {
    private val curator = "7c965d8c2acdfd635562da3bcb82596b595be28b008d8b54ec702ed4c67d9d25"

    private val description = "Fields for a bitcoin.mov entry: a Bitcoin movie, documentary, " +
        "short, interview or series, with at least one link to watch or look it up."

    /** The NIP's example, as published. */
    private val bitcoinMov: Array<Array<String>> = arrayOf(
        arrayOf("d", "bitcoin.mov"),
        arrayOf("title", "bitcoin.mov suggestion"),
        arrayOf("name", "bitcoin.mov"),
        arrayOf("description", description),
        arrayOf("k", "31888"),
        arrayOf("visibility", "public"),
        arrayOf("picture", "https://bitcoin.mov/icon.svg"),
        arrayOf("domain", "bitcoin.mov"),
        arrayOf("relay", "wss://ephemeral.mantra.press"),
        arrayOf("field", "identifier", "token", "required", "the-rise-and-rise-of-bitcoin-2014", "Identifier", "{\"tag\":\"d\",\"max\":80,\"derived\":true}"),
        arrayOf("field", "title", "text", "required", "The Rise and Rise of Bitcoin", "Title", "{\"max\":200}"),
        arrayOf("field", "year", "year", "optional", "2014", "Year", "{\"min\":1900,\"max\":2100}"),
        arrayOf("field", "type", "enum", "required", "documentary", "Type", "{\"options\":[\"movie\",\"documentary\",\"short\",\"interview\",\"series\",\"other\"]}"),
        arrayOf("field", "watchUrl", "url", "optional", "https://youtube.com/watch?v=…", "Watch / reference URL", "{\"tag\":\"r\",\"marker\":\"watch\",\"max\":500,\"repeat\":true}"),
        arrayOf("field", "imdbUrl", "url", "optional", "https://imdb.com/title/tt2821314", "IMDb URL", "{\"tag\":\"r\",\"marker\":\"imdb\",\"max\":500}"),
        arrayOf("field", "image", "image", "optional", "https://…/poster.jpg", "Poster image URL (https)", "{\"max\":500}"),
        arrayOf("field", "hashtags", "token", "optional", "bitcoin", "Hashtags", "{\"tag\":\"t\",\"max\":60,\"repeat\":true,\"derived\":true}"),
        arrayOf("field", "description", "longtext", "optional", "Why is this worth watching?", "Description / review", "{\"tag\":\"content\",\"max\":4000}"),
        arrayOf("require-any", "watchUrl", "imdbUrl"),
    )

    @Test
    fun `the bitcoin_mov schema reads as published`() {
        val schema = assertNotNull(CuratedSchemaEvent.parse(event()))

        assertEquals("bitcoin.mov", schema.identifier)
        assertEquals("bitcoin.mov suggestion", schema.title)
        assertEquals("bitcoin.mov", schema.name)
        assertEquals(description, schema.description)
        assertEquals(CuratedVisibility.Public, schema.visibility)
        assertEquals("https://bitcoin.mov/icon.svg", schema.picture)
        assertEquals("bitcoin.mov", schema.domain)
        assertEquals(31888, schema.suggestionKind)
        assertEquals(listOf("wss://ephemeral.mantra.press"), schema.relays)
        assertEquals(listOf(listOf("watchUrl", "imdbUrl")), schema.requireAny)
        assertEquals(emptyList(), schema.suggesters)

        assertEquals(
            listOf("identifier", "title", "year", "type", "watchUrl", "imdbUrl", "image", "hashtags", "description"),
            schema.fields.map { it.name }
        )

        // The positional parts and the config, on the field that uses most of them.
        val watchUrl = schema.fields.first { it.name == "watchUrl" }
        assertEquals(CuratedFieldType.Url, watchUrl.type)
        assertFalse(watchUrl.isRequired)
        assertEquals("https://youtube.com/watch?v=…", watchUrl.placeholder)
        assertEquals("Watch / reference URL", watchUrl.label)
        assertEquals("r", watchUrl.tag())
        assertEquals("watch", watchUrl.config.marker)
        assertEquals(500L, watchUrl.config.max)
        assertEquals(true, watchUrl.config.repeat)

        val type = schema.fields.first { it.name == "type" }
        assertEquals(listOf("movie", "documentary", "short", "interview", "series", "other"), type.config.options)

        val identifier = schema.fields.first { it.name == "identifier" }
        assertEquals("d", identifier.tag())
        assertEquals(true, identifier.config.derived)
        assertTrue(identifier.isRequired)

        // Derived fields are filled in by the client, so a form does not ask.
        assertEquals(
            listOf("title", "year", "type", "watchUrl", "imdbUrl", "image", "description"),
            schema.formFields().map { it.name }
        )
    }

    @Test
    fun `the domain stands in for the name wherever the list is shown`() {
        val schema = assertNotNull(CuratedSchemaEvent.parse(event()))
        assertEquals("bitcoin.mov", schema.displayName())

        val withoutDomain = assertNotNull(
            CuratedSchemaEvent.parse(event(tags = bitcoinMov.without("domain").replacing("name", "Bitcoin on screen")))
        )
        assertEquals("Bitcoin on screen", withoutDomain.displayName())
    }

    @Test
    fun `a schema round trips through the template`() {
        val read = assertNotNull(CuratedSchemaEvent.parse(event()))

        val template = CuratedSchemaEvent.template(read, createdAt = 1735689600)
        assertEquals(CuratedSchemaEvent.KIND, template.kind)
        // The content mirrors the description, for clients that read content.
        assertEquals(description, template.content)

        val reread = assertNotNull(CuratedSchemaEvent.parse(event(tags = template.tags, content = template.content)))
        assertEquals(read, reread)

        // And the tags come out in the NIP's order, identity first.
        assertEquals(
            listOf("d", "title", "name", "description", "k", "visibility", "picture", "domain"),
            template.tags.take(8).map { it[0] }
        )
        assertEquals(9, template.tags.count { it[0] == "field" })
        assertEquals(listOf("require-any", "watchUrl", "imdbUrl"), template.tags.first { it[0] == "require-any" }.toList())
        assertEquals(listOf("relay", "wss://ephemeral.mantra.press"), template.tags.last().toList())
    }

    @Test
    fun `a field tag is written the way the NIP shows it`() {
        val schema = assertNotNull(CuratedSchemaEvent.parse(event()))
        val tag = schema.fields.first { it.name == "watchUrl" }.toTagArray()

        assertEquals(
            listOf("field", "watchUrl", "url", "optional", "https://youtube.com/watch?v=…", "Watch / reference URL",
                "{\"tag\":\"r\",\"marker\":\"watch\",\"max\":500,\"repeat\":true}"),
            tag.toList()
        )
    }

    @Test
    fun `a config key this app does not know survives a round trip`() {
        // Other clients legitimately add their own. Editing a field here must
        // not strip what one of them meant by it.
        val field = assertNotNull(
            CuratedField.parse(arrayOf("field", "lang", "token", "optional", "en", "Language", "{\"max\":60,\"locale\":\"bcp47\"}"))
        )

        assertEquals(60L, field.config.max)
        assertEquals("\"bcp47\"", field.config.others["locale"].toString())
        assertEquals("{\"max\":60,\"locale\":\"bcp47\"}", field.config.toJson())
    }

    @Test
    fun `a malformed config costs the field its constraints and not its life`() {
        listOf("{not json", "[1,2]", "\"a string\"", "42", "").forEach { blob ->
            val field = assertNotNull(
                CuratedField.parse(arrayOf("field", "year", "year", "optional", "2014", "Year", blob)),
                "the field was dropped over the config \"$blob\""
            )
            assertEquals(CuratedFieldConfig(), field.config, "the config \"$blob\" read as something")
        }

        // A known key of the wrong type is dropped rather than kept as something
        // it is not; the rest of the blob is kept.
        val field = assertNotNull(
            CuratedField.parse(arrayOf("field", "year", "year", "optional", "", "", "{\"max\":\"lots\",\"min\":1900}"))
        )
        assertNull(field.config.max)
        assertEquals(1900L, field.config.min)
    }

    @Test
    fun `a date field reads as one with its bounds on the year`() {
        // bitcoin.necropolis.report's two, as published. Fourteen of its
        // entries lost their `died` to a build that dropped the type on the
        // way in and then wrote entries without it.
        val schema = assertNotNull(
            CuratedSchemaEvent.parse(
                event(
                    tags = bitcoinMov.plus(
                        listOf(
                            arrayOf("field", "founded", "date", "optional", "2010-07", "Founded", "{\"min\":1900,\"max\":2100,\"hint\":\"YYYY, YYYY-MM or YYYY-MM-DD.\"}"),
                            arrayOf("field", "died", "date", "required", "2014-02-28", "Died", "{\"min\":2009,\"max\":2100}"),
                        )
                    )
                )
            )
        )

        val died = schema.fields.first { it.name == "died" }
        assertEquals(CuratedFieldType.Date, died.type)
        assertTrue(died.isRequired)
        assertEquals("2014-02-28", died.placeholder)
        assertEquals("Died", died.label)
        assertEquals(2009L, died.config.min)
        assertEquals(2100L, died.config.max)

        // Its bounds are years, so `max` is a bound and not a length -- but the
        // value is not a number, and no keyboard should think it is.
        assertTrue(died.type.isBounded)
        assertFalse(died.type.isNumeric)

        assertEquals(listOf("founded", "died"), schema.formFields().takeLast(2).map { it.name })
        assertEquals(emptyList(), schema.unsupportedFields)

        assertEquals(
            listOf("field", "died", "date", "required", "2014-02-28", "Died", "{\"max\":2100,\"min\":2009}"),
            died.toTagArray().toList()
        )
    }

    @Test
    fun `a field with an unknown type is kept aside one with no name is dropped and the rest are kept`() {
        val stars = arrayOf("field", "rating", "stars", "required", "5", "Rating", "{\"max\":5}")
        val schema = assertNotNull(
            CuratedSchemaEvent.parse(
                event(
                    tags = bitcoinMov.plus(
                        listOf(
                            stars,
                            arrayOf("field", "  ", "text", "optional", "", "", "{}"),
                            arrayOf("field", "short"),
                        )
                    )
                )
            )
        )

        assertEquals(9, schema.fields.size)

        // A type this build does not know is not a type nobody knows. The
        // reader says which field it could not read rather than forgetting
        // it, so a writer can refuse to publish an entry without it.
        assertEquals(
            listOf(CuratedUnsupportedField("rating", "stars", isRequired = true, label = "Rating", tag = stars.toList())),
            schema.unsupportedFields
        )

        // And the template writes it back as it came, after the fields it does
        // know, so that an edit here cannot sign the list's rules away.
        val template = CuratedSchemaEvent.template(schema, createdAt = 1735689600)
        val fields = template.tags.filter { it[0] == "field" }
        assertEquals(10, fields.size)
        assertEquals(stars.toList(), fields.last().toList())
        assertEquals(schema, CuratedSchemaEvent.parse(event(tags = template.tags, content = template.content)))
    }

    @Test
    fun `a field missing its trailing positions still reads`() {
        val field = assertNotNull(CuratedField.parse(arrayOf("field", "director", "text")))

        assertEquals("director", field.name)
        assertFalse(field.isRequired)
        assertEquals("", field.placeholder)
        assertEquals("director", field.labelOrName())
        assertEquals(CuratedFieldConfig(), field.config)
    }

    @Test
    fun `a schema missing an identity tag is not usable`() {
        mapOf(
            "d" to CuratedSchemaProblem.IdentifierMissing,
            "title" to CuratedSchemaProblem.TitleMissing,
            "name" to CuratedSchemaProblem.NameMissing,
        ).forEach { (tag, problem) ->
            val tags = without(tag)
            assertNull(CuratedSchemaEvent.parse(event(tags = tags)), "a schema without \"$tag\" was accepted")
            assertEquals(listOf(problem), CuratedSchemaEvent.read(tags, description).problems())
        }

        // The description alone has a fallback: the content, which is where
        // generic clients put it. Empty both and it is missing.
        assertNull(CuratedSchemaEvent.parse(event(tags = without("description"), content = "")))
        assertEquals(
            listOf(CuratedSchemaProblem.DescriptionMissing),
            CuratedSchemaEvent.read(without("description"), "   ").problems()
        )
    }

    @Test
    fun `the description falls back to the content and the tag wins when both are there`() {
        val fromContent = assertNotNull(
            CuratedSchemaEvent.parse(event(tags = without("description"), content = "  What the content says.  "))
        )
        assertEquals("What the content says.", fromContent.description)

        val fromTag = assertNotNull(CuratedSchemaEvent.parse(event(content = "Something else entirely")))
        assertEquals(description, fromTag.description)
    }

    @Test
    fun `visibility is never guessed`() {
        assertNull(CuratedSchemaEvent.parse(event(tags = without("visibility"))))
        assertEquals(
            listOf(CuratedSchemaProblem.VisibilityMissing),
            CuratedSchemaEvent.read(without("visibility"), description).problems()
        )

        assertNull(CuratedSchemaEvent.parse(event(tags = replacing("visibility", "friends"))))
        assertNull(CuratedSchemaEvent.parse(event(tags = replacing("visibility", ""))))

        // Case and whitespace are forgiven; a fourth word is not.
        assertEquals(CuratedVisibility.Closed, CuratedSchemaEvent.parse(event(tags = replacing("visibility", " Closed ")))?.visibility)
        assertEquals(CuratedVisibility.Private, CuratedSchemaEvent.parse(event(tags = replacing("visibility", "PRIVATE")))?.visibility)
    }

    @Test
    fun `a schema with no fields is not usable`() {
        val tags = bitcoinMov.filterNot { it[0] == "field" }.toTypedArray()

        assertNull(CuratedSchemaEvent.parse(event(tags = tags)))
        assertEquals(listOf(CuratedSchemaProblem.NoFields), CuratedSchemaEvent.read(tags, description).problems())

        // Checked before the mandatory fields are put back, which is the NIP's
        // rule: "no field tags" is about what the publisher said.
        val onlyUnparseable = tags.plus(arrayOf("field", "rating", "stars", "optional", "", "", "{}"))
        assertNull(CuratedSchemaEvent.parse(event(tags = onlyUnparseable)))
    }

    @Test
    fun `a picture that is not https is not usable`() {
        listOf("http://bitcoin.mov/icon.svg", "bitcoin.mov/icon.svg", "javascript:alert(1)", "https://").forEach { picture ->
            assertNull(
                CuratedSchemaEvent.parse(event(tags = replacing("picture", picture))),
                "a schema with the picture \"$picture\" was accepted"
            )
        }

        // Absent is fine; it is optional.
        assertNull(CuratedSchemaEvent.parse(event(tags = without("picture")))?.picture)
        assertTrue(CuratedSchemaEvent.read(without("picture"), description).isUsable())
    }

    @Test
    fun `a domain that is not a hostname is not usable and one that is gets normalized`() {
        listOf("bitcoin", "localhost", "bit coin.mov", "-bitcoin.mov", "bitcoin_mov.example").forEach { domain ->
            assertNull(
                CuratedSchemaEvent.parse(event(tags = replacing("domain", domain))),
                "a schema with the domain \"$domain\" was accepted"
            )
        }

        // Lenient on input, strict on what is kept: the things people paste
        // around a hostname come off, and what is left is what is checked.
        assertEquals("bitcoin.mov", CuratedSchemaEvent.parse(event(tags = replacing("domain", "https://Bitcoin.MOV/")))?.domain)
        assertEquals("bitcoin.mov", CuratedSchemaEvent.parse(event(tags = replacing("domain", "bitcoin.mov:8080/x y")))?.domain)
        assertEquals("bitcoin.mov", CuratedSchemaEvent.normalizeDomain("@Bitcoin.mov:443/path."))
        assertTrue(CuratedSchemaEvent.isDomain("sub.example.co.za"))
        assertFalse(CuratedSchemaEvent.isDomain("example"))
    }

    @Test
    fun `a relay that is not a relay is not usable`() {
        listOf("https://ephemeral.mantra.press", "ephemeral.mantra.press", "wss://").forEach { relay ->
            assertNull(
                CuratedSchemaEvent.parse(event(tags = replacing("relay", relay))),
                "a schema naming the relay \"$relay\" was accepted"
            )
        }

        // The NIP allows ws://, whatever this app will let a group sign.
        assertEquals(listOf("ws://localhost:7777"), CuratedSchemaEvent.parse(event(tags = replacing("relay", "ws://localhost:7777")))?.relays)

        // Duplicates collapse; none at all is allowed.
        assertEquals(
            listOf("wss://ephemeral.mantra.press"),
            CuratedSchemaEvent.parse(event(tags = bitcoinMov.plus(arrayOf("relay", "wss://ephemeral.mantra.press"))))?.relays
        )
        assertEquals(emptyList(), CuratedSchemaEvent.parse(event(tags = without("relay")))?.relays)
    }

    @Test
    fun `an over-long identity tag is not usable`() {
        assertNull(CuratedSchemaEvent.parse(event(tags = replacing("name", "n".repeat(101)))))
        assertNull(CuratedSchemaEvent.parse(event(tags = replacing("d", "d".repeat(101)))))
        assertNull(CuratedSchemaEvent.parse(event(tags = replacing("title", "t".repeat(201)))))
        assertNull(CuratedSchemaEvent.parse(event(tags = replacing("description", "x".repeat(501)))))

        assertNotNull(CuratedSchemaEvent.parse(event(tags = replacing("name", "n".repeat(100)))))
    }

    @Test
    fun `the wrong kind is not a schema whatever its tags say`() {
        assertNull(CuratedSchemaEvent.parse(event(kind = 31888)))
        assertNull(CuratedSchemaEvent.parse(event(kind = 30023)))
    }

    @Test
    fun `the identifier and the title are forced in whatever the schema says`() {
        // A schema fetched from a hostile relay cannot talk a client into
        // accepting untitled or unaddressable entries.
        val onlyAYear = arrayOf(
            arrayOf("d", "years"),
            arrayOf("title", "A year"),
            arrayOf("name", "Years"),
            arrayOf("description", "Just years."),
            arrayOf("visibility", "public"),
            arrayOf("field", "year", "year", "optional", "", "", "{}"),
        )

        val schema = assertNotNull(CuratedSchemaEvent.parse(event(tags = onlyAYear)))

        assertEquals(listOf("d", "title", "year"), schema.fields.map { it.tag() })
        assertTrue(schema.fields[0].isRequired)
        assertTrue(schema.fields[1].isRequired)
        assertEquals(CuratedField.IDENTIFIER, schema.fields[0])
        assertEquals(CuratedField.TITLE, schema.fields[1])

        // And a title the publisher relaxed is made required again, in place.
        val relaxed = replacing(
            "field",
            "title",
            arrayOf("field", "title", "text", "optional", "The Rise and Rise of Bitcoin", "Title", "{\"max\":200}")
        )
        val repaired = assertNotNull(CuratedSchemaEvent.parse(event(tags = relaxed)))
        assertEquals(9, repaired.fields.size)
        assertTrue(repaired.fields.first { it.name == "title" }.isRequired)

        // Running it again changes nothing.
        assertEquals(repaired, repaired.normalized())
    }

    @Test
    fun `a require-any naming fewer than two fields says nothing`() {
        val schema = assertNotNull(
            CuratedSchemaEvent.parse(
                event(
                    tags = bitcoinMov.plus(
                        listOf(
                            arrayOf("require-any", "image"),
                            arrayOf("require-any"),
                            arrayOf("require-any", "year", "  ", "type"),
                        )
                    )
                )
            )
        )

        assertEquals(listOf(listOf("watchUrl", "imdbUrl"), listOf("year", "type")), schema.requireAny)
    }

    @Test
    fun `suggesters are the p tags that are pubkeys`() {
        val other = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"
        val schema = assertNotNull(
            CuratedSchemaEvent.parse(
                event(
                    tags = replacing("visibility", "closed").plus(
                        listOf(
                            arrayOf("p", other),
                            arrayOf("p", other.uppercase()),
                            arrayOf("p", "not a pubkey"),
                        )
                    )
                )
            )
        )

        assertEquals(listOf(other), schema.suggesters)
    }

    @Test
    fun `the coordinate is what a suggestion replies to`() {
        assertEquals("31889:$curator:bitcoin.mov", CuratedSchemaEvent.coordinate(curator, "bitcoin.mov"))

        // An identifier may itself hold colons, so the coordinate does too.
        assertEquals("31889:$curator:imdb:tt2821314", CuratedSchemaEvent.coordinate(curator, "imdb:tt2821314"))
    }

    @Test
    fun `an empty config is written as an empty object`() {
        assertEquals("{}", CuratedFieldConfig().toJson())
        assertEquals("{\"tag\":\"d\",\"max\":80,\"derived\":true}", CuratedField.IDENTIFIER.config.toJson())
    }

    private fun event(
        tags: Array<Array<String>> = bitcoinMov,
        content: String = description,
        kind: Int = CuratedSchemaEvent.KIND,
    ): Event = Event(
        id = "a".repeat(64),
        pubKey = curator,
        createdAt = 1735689600,
        kind = kind,
        tags = tags,
        content = content,
        sig = "b".repeat(128),
    )

    private fun without(tag: String): Array<Array<String>> = bitcoinMov.without(tag)

    private fun replacing(tag: String, value: String): Array<Array<String>> = bitcoinMov.replacing(tag, value)

    private fun replacing(tag: String, name: String, with: Array<String>): Array<Array<String>> =
        bitcoinMov.map { if (it[0] == tag && it[1] == name) with else it }.toTypedArray()

    private fun Array<Array<String>>.without(tag: String): Array<Array<String>> =
        filter { it[0] != tag }.toTypedArray()

    private fun Array<Array<String>>.replacing(tag: String, value: String): Array<Array<String>> =
        map { if (it[0] == tag) arrayOf(tag, value) else it }.toTypedArray()
}
