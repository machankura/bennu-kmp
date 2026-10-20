package com.machankura.bennu.compose.nostr.nip30303

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.machankura.bennu.compose.nostr.nip30303.tags.ArtifactVersionMetadataTag

/**
 * The version an artifact starts life with, built to be signed beside it.
 *
 * It used to be derived on arrival from the label the artifact carries, because
 * proposing it on its own would have cost a second quorum for one form. A batch
 * costs one quorum, so it is signed now -- and a chapter hangs off this row,
 * which is the reason to care: a version nobody signed is a parent vouched for
 * by its own signed children rather than the other way round.
 *
 * What has to hold is that every input comes off the artifact. The id is not
 * settled here -- the signing session hashes it under the group's key -- so an
 * input read from anywhere else would not show up as a wrong row. It would show
 * up as two devices proposing two different batches, which reads as a batch that
 * simply never aggregates.
 */
class InitialArtifactVersionTest {
    private val groupKey = "a".repeat(64)
    private val dialectId = "b".repeat(64)

    /**
     * The artifact as the session will sign it: the proposer's fields re-authored
     * under the group's key. This is what `proposeSigningBatch` hands to
     * `dependents`, so it is what the version has to be built from.
     */
    private fun leadArtifact(
        versionLabel: String = "1.0",
        createdAt: Long = 1_700_000_000,
    ): Event {
        val template = ArtifactEvent.build(
            name = "In Detention",
            url = "example.com",
            visibility = "private",
            license = "cc",
            dialectId = dialectId,
            versionLabel = versionLabel,
            createdAt = createdAt,
        )

        return Event(
            id = EventHasher.hashId(
                pubKey = groupKey,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content
            ),
            pubKey = groupKey,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = ""
        )
    }

    /** The version item as the session would author it, so the accessors read. */
    private fun versionOf(artifact: Event): ArtifactVersionEvent? =
        ArtifactVersionEvent.initialVersionOf(artifact).singleOrNull()?.let { template ->
            ArtifactVersionEvent(
                id = EventHasher.hashId(
                    pubKey = groupKey,
                    createdAt = template.createdAt,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content
                ),
                pubKey = groupKey,
                createdAt = template.createdAt,
                tags = template.tags,
                content = template.content,
                sig = ""
            )
        }

    @Test
    fun `the version names the artifact it was built from`() {
        // The whole reason this takes the lead event rather than a form's fields:
        // the id it carries is a hash over the group's key at the room's path, so
        // there is no artifact to name until the proposal has been authored.
        val artifact = leadArtifact()

        assertEquals(artifact.id, versionOf(artifact)?.artifactId())
    }

    @Test
    fun `it carries the label the artifact declared`() {
        val artifact = leadArtifact(versionLabel = "First Edition")

        assertEquals("First Edition", versionOf(artifact)?.content)
    }

    @Test
    fun `it takes the artifact's own timestamp rather than the clock`() {
        // So the batch reads as one act. Reading the clock here would agree with
        // itself on one device and disagree between two that proposed the same
        // artifact a second apart -- which is a batch nobody can aggregate, and
        // the case nobody can reproduce on demand.
        val artifact = leadArtifact(createdAt = 1_700_000_042)

        assertEquals(1_700_000_042, versionOf(artifact)?.createdAt)
    }

    @Test
    fun `an artifact declares exactly one first version`() {
        // Item 0 is the artifact and item 1 is this, and a chapter hangs off
        // whichever version it finds. Two would make that a coin toss.
        assertEquals(1, ArtifactVersionEvent.initialVersionOf(leadArtifact()).size)
    }

    @Test
    fun `two devices build the same version from the same artifact`() {
        // Every signer authors the batch itself and signs the ids it arrives at.
        // Devices that disagree here produce partial signatures over different
        // messages, and the session stalls rather than saying why.
        val artifact = leadArtifact()

        assertEquals(versionOf(artifact)?.id, versionOf(artifact)?.id)
    }

    @Test
    fun `artifacts alike but for the label do not share a version`() {
        // The label is bound into the id rather than hung beside it, so renaming
        // a version cannot leave the row it replaces standing.
        val first = versionOf(leadArtifact(versionLabel = "1.0"))
        val second = versionOf(leadArtifact(versionLabel = "2.0"))

        assertNotEquals(first?.id, second?.id)
    }

    @Test
    fun `an artifact that declares no version proposes none`() {
        // Artifacts written before the artifact carried its first version. The
        // batch is then one item, which is what an artifact proposal used to be.
        val declared = leadArtifact()
        val silent = Event(
            id = declared.id,
            pubKey = declared.pubKey,
            createdAt = declared.createdAt,
            kind = declared.kind,
            tags = declared.tags.filterNot { it.firstOrNull() == ArtifactVersionMetadataTag.TAG_NAME }
                .toTypedArray(),
            content = declared.content,
            sig = declared.sig
        )

        assertTrue(ArtifactVersionEvent.initialVersionOf(silent).isEmpty())
        assertNull(versionOf(silent))
    }
}
