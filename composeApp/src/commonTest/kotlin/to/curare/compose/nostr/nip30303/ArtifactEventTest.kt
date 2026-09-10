package to.curare.compose.nostr.nip30303

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import to.curare.compose.nostr.nip30303.tags.ArtifactVersionMetadataTag

/**
 * What the form collects has to reach the members deciding whether to sign it.
 *
 * An artifact proposal leaves the proposer's device as a kind, a tag array and
 * a string, and everything a member is shown before signing -- and every row
 * built afterwards -- is read back out of those. A field that does not survive
 * the trip is not a visible failure: the artifact still appears, just without
 * a url, or a source dialect, or a first version, on every device but the one
 * that typed it.
 */
class ArtifactEventTest {
    private val groupKey = "a".repeat(64)
    private val dialectId = "b".repeat(64)

    /** The event a member actually receives: bytes, with no template behind it. */
    private fun readBack(template: com.vitorpamplona.quartz.nip01Core.signers.EventTemplate<ArtifactEvent>) =
        ArtifactEvent(
            id = EventHasher.hashId(
                pubKey = groupKey,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content,
            ),
            pubKey = groupKey,
            createdAt = template.createdAt,
            tags = template.tags,
            content = template.content,
            sig = "c".repeat(128),
        )

    private fun proposal(versionLabel: String = "1.0") = ArtifactEvent.build(
        name = "In Detention",
        url = "https://example.com/in-detention",
        visibility = "private",
        license = "cc",
        dialectId = dialectId,
        versionLabel = versionLabel,
        createdAt = 1_700_000_000L,
    )

    @Test
    fun `every field the form collects survives the trip through the tags`() {
        val artifact = readBack(proposal())

        assertEquals("In Detention", artifact.content)
        assertEquals("https://example.com/in-detention", artifact.url())
        assertEquals("private", artifact.visibility())
        assertEquals("cc", artifact.license())
        assertEquals(dialectId, artifact.dialectId())
        assertEquals("1.0", artifact.versionLabel())
    }

    @Test
    fun `the proposal is the artifact's kind, so the signing screen can describe it`() {
        // The session carries a kind and nothing else to go on. Get this wrong
        // and members are asked to sign "event of kind 30300" -- a question
        // nobody can answer.
        assertEquals(ArtifactEvent.KIND, proposal().kind)
    }

    @Test
    fun `an artifact declares one version, whatever an initializer adds`() {
        // addUnique, not add: two labels would leave receivers deriving two
        // different first versions depending on which one they read first.
        val template = ArtifactEvent.build(
            name = "In Detention",
            url = "https://example.com/in-detention",
            visibility = "private",
            license = "cc",
            dialectId = dialectId,
            versionLabel = "1.0",
        ) {
            addUnique(ArtifactVersionMetadataTag.assemble("2.0"))
        }

        assertEquals(
            1,
            template.tags.count { it.firstOrNull() == ArtifactVersionMetadataTag.TAG_NAME }
        )
    }

    @Test
    fun `an artifact from before the label existed reads back as declaring none`() {
        // Not an error: it is what every artifact submitted the old way looks
        // like, and they have to keep parsing rather than failing to load.
        val template = proposal()
        val older = Event(
            id = "d".repeat(64),
            pubKey = groupKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags
                .filterNot { it.firstOrNull() == ArtifactVersionMetadataTag.TAG_NAME }
                .toTypedArray(),
            content = template.content,
            sig = "",
        )

        val artifact = ArtifactEvent(
            older.id, older.pubKey, older.createdAt, older.tags, older.content, older.sig
        )

        assertNull(artifact.versionLabel())
        // Everything else still reads, so the artifact itself is unharmed.
        assertEquals(dialectId, artifact.dialectId())
        assertEquals("https://example.com/in-detention", artifact.url())
    }
}
