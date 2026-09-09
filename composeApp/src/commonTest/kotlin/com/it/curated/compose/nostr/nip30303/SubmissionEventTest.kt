package com.it.curated.compose.nostr.nip30303

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * What a submission has to survive: the trip through a group.
 *
 * The envelope is only worth having if the event inside it comes out the other
 * side unchanged -- same id, same author, same signature. The moment any of
 * those is rewritten in transit, a group can no longer hold work by anyone but
 * its own members, which is the whole reason submissions exist.
 */
class SubmissionEventTest {
    private val submitter = "a".repeat(64)
    private val outsider = "b".repeat(64)

    /** A dialect written by somebody who is not in the group. */
    private fun outsiderDialect(): Event {
        val template = DialectEvent.build(
            name = "Sesotho",
            country = "Lesotho",
            language = "st",
            createdAt = 1_700_000_000L,
        )
        return Event(
            id = EventHasher.hashId(
                pubKey = outsider,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content,
            ),
            pubKey = outsider,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = "c".repeat(128),
        )
    }

    /** Send a submission template and read it back the way inbound does. */
    private fun roundTrip(payload: Event): SubmissionEvent {
        val template = SubmissionEvent.build(payload = payload, createdAt = 1_700_000_100L)
        val onTheWire = Event.fromJson(
            Event(
                id = EventHasher.hashId(
                    pubKey = submitter,
                    createdAt = template.createdAt,
                    kind = template.kind,
                    tags = template.tags,
                    content = template.content,
                ),
                pubKey = submitter,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content,
                sig = "",
            ).toJson()
        )

        return SubmissionEvent(
            id = onTheWire.id,
            pubKey = onTheWire.pubKey,
            createdAt = onTheWire.createdAt,
            tags = onTheWire.tags,
            content = onTheWire.content,
            sig = onTheWire.sig,
        )
    }

    @Test
    fun `the payload comes back as the event that went in`() {
        val dialect = outsiderDialect()

        val payload = roundTrip(dialect).payload()

        assertEquals(dialect.id, payload?.id)
        assertEquals(dialect.pubKey, payload?.pubKey)
        assertEquals(dialect.kind, payload?.kind)
        assertEquals(dialect.content, payload?.content)
        assertEquals(dialect.sig, payload?.sig)
    }

    @Test
    fun `submitting does not make the submitter the author`() {
        val dialect = outsiderDialect()

        val submission = roundTrip(dialect)

        assertEquals(submitter, submission.pubKey)
        assertEquals(outsider, submission.payload()?.pubKey)
        assertNotEquals(submission.pubKey, submission.payload()?.pubKey)
    }

    @Test
    fun `the envelope names what it carries without being opened`() {
        val dialect = outsiderDialect()

        val submission = roundTrip(dialect)

        assertEquals(DialectEvent.KIND, submission.payloadKind())
        assertEquals(dialect.id, submission.payloadId())
        assertEquals(outsider, submission.payloadAuthor())
    }

    @Test
    fun `a payload the group cannot read is null rather than empty`() {
        val submission = SubmissionEvent(
            id = "d".repeat(64),
            pubKey = submitter,
            createdAt = 1_700_000_100L,
            tags = arrayOf(),
            content = "not an event",
            sig = "",
        )

        assertNull(submission.payload())
    }
}
