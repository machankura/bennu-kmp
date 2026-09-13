package to.curare.compose.nostr.curated

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The shapes a list can start from, held to what curare.to's own test holds
 * its templates to: each one publishes a schema the module verifies, with its
 * rule recorded and no derived `t` field.
 *
 * The rest is what this app's editor adds on top. A template is seeded
 * straight into the working copy, so every field has to be one the editor
 * would have let a member commit, and every rule has to name fields that are
 * there -- a template that broke either would put the editor in a state the
 * editor itself refuses to reach.
 */
class CuratedSchemaTemplateTest {
    private val curator = "7c965d8c2acdfd635562da3bcb82596b595be28b008d8b54ec702ed4c67d9d25"

    /** A schema from a template, as the editor would propose it. */
    private fun schemaFrom(template: CuratedSchemaTemplate) = CuratedSchema(
        identifier = template.key,
        title = "${template.key} suggestion",
        name = template.key,
        description = "A list started from the ${template.key} template.",
        visibility = CuratedVisibility.Public,
        fields = template.fields,
        requireAny = template.requireAny,
        relays = listOf("wss://relay.example"),
    )

    @Test
    fun `every template publishes a schema a reader accepts, and reads back as itself`() {
        for (template in CuratedSchemaTemplate.entries) {
            val schema = schemaFrom(template)
            assertEquals(emptyList(), schema.problems(), "${template.key} is not a usable schema")

            // The mandatory two are there and required, so the one repair a
            // reader makes has nothing to do: what the member sees is what the
            // group signs.
            assertEquals(schema, schema.normalized(), "${template.key} is changed by normalizing")

            val built = CuratedSchemaEvent.template(schema, createdAt = 1_735_689_600)
            val read = assertNotNull(CuratedSchemaEvent.parse(event(built.tags, built.content)))
            assertEquals(schema, read, "${template.key} did not round trip")
        }
    }

    @Test
    fun `every template records its rule on the identifier and declares no hashtag field`() {
        for (template in CuratedSchemaTemplate.entries) {
            val identifier = template.fields.single { it.tag() == "d" }
            assertEquals(true, identifier.config.derived, "${template.key}'s identifier is prompted for")
            assertTrue(
                identifier.config.hint.orEmpty().endsWith("[rule:${template.rule.key}]"),
                "${template.key}'s identifier does not record its rule: ${identifier.config.hint}"
            )

            // The hashtag rule is bitcoin.mov's own; a list is its own topic.
            assertTrue(template.fields.none { it.tag() == "t" }, "${template.key} declares a t field")
        }
    }

    @Test
    fun `the hint is the one curare_to writes`() {
        // Byte for byte, because that site's `ruleOf` reads the mark back with a
        // regular expression and a template started there and here should
        // produce the same schema.
        assertEquals(
            "Derived by the client — the link, else the title and author. [rule:link-or-title]",
            CuratedDeriveRule.LinkOrTitle.hint()
        )
        assertEquals(
            "Derived by the client — the title. [rule:title]",
            CuratedDeriveRule.Title.hint()
        )
    }

    @Test
    fun `every template is one the editor would have let a member build`() {
        for (template in CuratedSchemaTemplate.entries) {
            val names = template.fields.map { it.name }
            assertTrue(
                names.all { it.isNotBlank() && it.none(Char::isWhitespace) },
                "${template.key} has a field name the editor refuses: $names"
            )
            assertEquals(names.distinct(), names, "${template.key} names a field twice")

            for (group in template.requireAny) {
                assertTrue(group.size > 1, "${template.key} has a rule that says nothing: $group")
                assertTrue(
                    group.all { it in names },
                    "${template.key} has a rule naming a field it does not have: $group"
                )
            }

            template.fields.filter { it.type == CuratedFieldType.Enum }.forEach { field ->
                assertTrue(
                    !field.config.options.isNullOrEmpty(),
                    "${template.key}'s ${field.name} is an enum with nothing to choose from"
                )
            }
        }
    }

    @Test
    fun `the templates are the site's four, in its order`() {
        assertEquals(
            listOf("links", "discussion", "catalogue", "custom"),
            CuratedSchemaTemplate.entries.map { it.key }
        )

        // The reddit-shaped default, by name: what an entry asks for and what
        // it needs one of.
        val links = CuratedSchemaTemplate.Links
        assertEquals(
            listOf("identifier", "title", "link", "body", "image", "flair"),
            links.fields.map { it.name }
        )
        assertEquals(listOf(listOf("link", "body")), links.requireAny)
        assertEquals("r", links.fields.first { it.name == "link" }.tag())
        assertEquals(CuratedFieldConfig.CONTENT_TAG, links.fields.first { it.name == "body" }.tag())

        // Custom is the two fields every list has and nothing else.
        assertEquals(listOf("d", "title"), CuratedSchemaTemplate.Custom.fields.map { it.tag() })
        assertEquals(emptyList(), CuratedSchemaTemplate.Custom.requireAny)
    }

    @Test
    fun `a key finds its template, and anything else is custom`() {
        for (template in CuratedSchemaTemplate.entries) {
            assertSame(template, CuratedSchemaTemplate.byKey(template.key))
        }

        // Custom rather than the first: the one shape that presumes nothing
        // about a list it was not asked for.
        assertSame(CuratedSchemaTemplate.Custom, CuratedSchemaTemplate.byKey(null))
        assertSame(CuratedSchemaTemplate.Custom, CuratedSchemaTemplate.byKey("gallery"))
        assertSame(CuratedSchemaTemplate.Custom, CuratedSchemaTemplate.byKey("Links"))
    }

    private fun event(tags: Array<Array<String>>, content: String): Event = Event(
        id = "a".repeat(64),
        pubKey = curator,
        createdAt = 1_735_689_600,
        kind = CuratedSchemaEvent.KIND,
        tags = tags,
        content = content,
        sig = "b".repeat(128),
    )
}
