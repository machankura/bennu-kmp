package com.machankura.bennu.compose.nostr.chronicle

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.machankura.bennu.compose.nostr.chronicle.tags.ChronicleIdTag
import com.machankura.bennu.compose.nostr.chronicle.tags.ChroniclePageTag
import com.machankura.bennu.compose.nostr.chronicle.tags.ChronicleRecipientTag
import com.machankura.bennu.compose.nostr.frost.FrostSigningEvents
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.nip30303.ArtifactEvent
import com.machankura.bennu.compose.nostr.nip30303.ArtifactVersionEvent
import com.machankura.bennu.compose.nostr.nip30303.ChapterEvent
import com.machankura.bennu.compose.nostr.nip30303.ChunkEvent
import com.machankura.bennu.compose.nostr.nip30303.DialectEvent
import com.machankura.bennu.compose.nostr.nip30303.SubmissionEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationArtifactVersionContributorListEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationArtifactVersionEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationChapterContributorListEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationChapterEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationChunkEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationContributorListEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationEvent

/**
 * The envelope a chronicle travels in: what it carries, what it refuses, and the
 * order it puts things in.
 *
 * Nothing here verifies a signature -- that is `GroupKeyStateTest`'s half, and
 * `ChronicleManager`'s at apply time. What these cover is the framing, and the two
 * bounds a page has to hold whoever sent it.
 */
class ChronicleEventTest {
    private val recipient = "d".repeat(64)
    private val chronicleId = "e".repeat(64)

    private fun payload(
        kind: Int,
        content: String = "payload",
        id: String = "a".repeat(63) + (kind % 10),
    ) = Event(
        id = id,
        pubKey = "b".repeat(64),
        createdAt = 1_700_000_000L,
        kind = kind,
        tags = arrayOf(arrayOf("alt", "test")),
        content = content,
        sig = "c".repeat(128)
    )

    // ---- The codec -------------------------------------------------------

    @Test
    fun `what is encoded is what comes back whole`() {
        val events = listOf(
            payload(DialectEvent.KIND, "isiZulu"),
            payload(ArtifactEvent.KIND, "an artifact"),
        )

        val decoded = ChronicleEvent.decodePage(ChronicleEvent.encodePage(events))

        assertNotNull(decoded)
        assertEquals(events.map { it.content }, decoded.map { it.content })
        // The point of carrying whole events rather than rebuilt ones: the
        // signature is what the receiver checks, so losing it in transit would
        // leave them nothing to check.
        assertEquals(events.map { it.id }, decoded.map { it.id })
        assertEquals(events.map { it.pubKey }, decoded.map { it.pubKey })
        assertEquals(events.map { it.sig }, decoded.map { it.sig })
    }

    @Test
    fun `a page of one is still an array`() {
        // The contrast with FrostSigningEvents.encodeProposal, which keeps a bare
        // object for a batch of one so that builds predating batching can read
        // it. Both chronicle kinds are new, so there is no such build and no reason
        // to carry the second shape.
        val encoded = ChronicleEvent.encodePage(listOf(payload(DialectEvent.KIND)))

        assertTrue(encoded.startsWith("["), "a page is always an array")
        assertTrue(encoded.endsWith("]"))
        assertEquals(1, ChronicleEvent.decodePage(encoded)?.size)
    }

    @Test
    fun `a page that is not an array is not a page`() {
        assertNull(ChronicleEvent.decodePage(payload(DialectEvent.KIND).toJson()))
        assertNull(ChronicleEvent.decodePage(""))
        assertNull(ChronicleEvent.decodePage("not json"))
        assertNull(ChronicleEvent.decodePage("[unclosed"))
    }

    @Test
    fun `an empty page is refused rather than read as an empty chronicle`() {
        assertNull(ChronicleEvent.decodePage("[]"))
    }

    @Test
    fun `one unreadable payload refuses the whole page`() {
        // All-or-nothing here, and per-payload at verify time. They answer
        // different questions: a page that will not parse has lost its framing,
        // so a page quietly shortened by one element would report a complete
        // chronicle on its page count while holding less than it says. A payload
        // whose signature does not verify is a well-framed page with one bad
        // event in it, and dropping its honest neighbours would let one forgery
        // deny an entire chronicle.
        val good = payload(DialectEvent.KIND)

        assertNull(ChronicleEvent.decodePage("[${good.toJson()},\"not an event\"]"))
        assertNull(ChronicleEvent.decodePage("[\"not an event\",${good.toJson()}]"))
    }

    // ---- The two caps ----------------------------------------------------

    @Test
    fun `a page over the event cap is refused on the way in`() {
        val overCap = (0..ChronicleEvent.MAX_PAGE_EVENTS).map {
            payload(DialectEvent.KIND, id = it.toString().padStart(64, '0'))
        }
        assertEquals(ChronicleEvent.MAX_PAGE_EVENTS + 1, overCap.size)

        assertNull(ChronicleEvent.decodePage(ChronicleEvent.encodePage(overCap)))

        // And exactly at the cap is fine, so the bound is not off by one.
        assertNotNull(ChronicleEvent.decodePage(ChronicleEvent.encodePage(overCap.dropLast(1))))
    }

    @Test
    fun `the event cap can actually be reached under the byte cap`() {
        // The cap that cannot fire. An event is over 300 bytes before it says
        // anything -- 64 characters of id, 64 of pubkey, 128 of signature -- so a
        // count cap set above what MAX_PAGE_BYTES can hold is a check that never
        // runs, and the page-over-the-cap test above passes for the wrong reason.
        // Both caps have to be re-sized together; this is what says so.
        val atCap = (0 until ChronicleEvent.MAX_PAGE_EVENTS).map {
            payload(DialectEvent.KIND, id = it.toString().padStart(64, '0'))
        }
        val page = ChronicleEvent.encodePage(atCap)

        assertTrue(
            page.encodeToByteArray().size <= ChronicleEvent.MAX_PAGE_BYTES,
            "MAX_PAGE_EVENTS payloads take ${page.encodeToByteArray().size} bytes, " +
                "over the ${ChronicleEvent.MAX_PAGE_BYTES}-byte cap: the event cap can never fire"
        )
        assertEquals(ChronicleEvent.MAX_PAGE_EVENTS, ChronicleEvent.decodePage(page)?.size)
    }

    @Test
    fun `a page over the byte cap is refused on the way in`() {
        // Checked before the JSON is parsed, because the cap exists to bound the
        // work a remote party can ask this device to do. A chronicle is the second
        // place in this protocol where somebody else sets that size.
        val huge = ChronicleEvent.encodePage(
            listOf(payload(DialectEvent.KIND, content = "x".repeat(ChronicleEvent.MAX_PAGE_BYTES)))
        )
        assertTrue(huge.encodeToByteArray().size > ChronicleEvent.MAX_PAGE_BYTES)

        assertNull(ChronicleEvent.decodePage(huge))
    }

    @Test
    fun `the byte cap counts bytes not characters`() {
        // A multi-byte payload that fits as characters and does not as bytes. If
        // this were measured in characters the cap would let through up to four
        // times what it says.
        val wide = "世".repeat(ChronicleEvent.MAX_PAGE_BYTES / 2)
        val page = ChronicleEvent.encodePage(listOf(payload(DialectEvent.KIND, content = wide)))

        assertTrue(page.length < ChronicleEvent.MAX_PAGE_BYTES)
        assertTrue(page.encodeToByteArray().size > ChronicleEvent.MAX_PAGE_BYTES)
        assertNull(ChronicleEvent.decodePage(page))
    }

    // ---- What a chronicle may carry ---------------------------------------

    @Test
    fun `the allowlist is every kind the group signs and only those`() {
        listOf(
            DialectEvent.KIND,
            ArtifactEvent.KIND,
            ArtifactVersionEvent.KIND,
            ChapterEvent.KIND,
            ChunkEvent.KIND,
            TranslationArtifactVersionEvent.KIND,
            TranslationChapterEvent.KIND,
            TranslationChunkEvent.KIND,
        ).forEach { assertTrue(ChronicleEvent.isChroniclable(it), "kind $it should be chroniclable") }

        assertEquals(8, ChronicleEvent.CHRONICLABLE_KINDS.size)
    }

    @Test
    fun `the translated text is chroniclable now that the group signs it`() {
        // Worth its own case because it was the feature's headline limitation for
        // as long as `saveTranslation` wrote the row and queued the member's own
        // rumor. A chronicle can only carry what the receiver can check, so a
        // translation nobody signed could not travel, and a new member got the
        // whole structure and none of the prose.
        assertTrue(ChronicleEvent.isChroniclable(TranslationChunkEvent.KIND))

        // Same shape, same fix: the version an artifact starts life with used to
        // be derived on arrival, so it was authored by the group with no
        // signature to show for it. It is the second item of the artifact's batch
        // now.
        assertTrue(ChronicleEvent.isChroniclable(ArtifactVersionEvent.KIND))
    }

    @Test
    fun `the kinds nobody signs are still left out`() {
        // Nothing builds one of these at all; the inbound arm exists and no
        // producer does.
        assertFalse(ChronicleEvent.isChroniclable(TranslationEvent.KIND))
    }

    @Test
    fun `a chronicle may not carry a key state however well signed`() {
        // The attack the allowlist exists for, and the reason it is an allowlist
        // rather than a denylist. A GroupKeyStateEvent is signed by the room and
        // passes verification perfectly, so a chronicle carrying an old one is a
        // validly signed statement about what the room signs with, replayed by
        // whoever kept a copy. Nothing but this stops it.
        assertFalse(ChronicleEvent.isChroniclable(GroupKeyStateEvent.KIND))
        assertNull(ChronicleEvent.applyRank(GroupKeyStateEvent.KIND))
    }

    @Test
    fun `a chronicle may not carry a birth certificate either`() {
        // The same shape as the key state above: signed by the room, passes
        // verification perfectly, and says something standing about groups rather
        // than carrying the group's work.
        //
        // The argument for letting it through is better than it is for a key
        // state -- "P certified C" is a fixed historical fact, where a key state
        // decides what a live room signs with -- and the cost of leaving it out
        // is real: a member added to a parent after a subgroup was made sees an
        // empty subgroup list. It is still left out here, because admitting it
        // needs an apply-order slot and a decision about whether a room's
        // chronicle may carry an event its own key did not sign, which no
        // chroniclable kind does. See docs/subgroups.md.
        assertFalse(ChronicleEvent.isChroniclable(SubgroupBirthCertificateEvent.KIND))
        assertNull(ChronicleEvent.applyRank(SubgroupBirthCertificateEvent.KIND))
    }

    @Test
    fun `a chronicle carries documents not envelopes or protocol`() {
        assertFalse(ChronicleEvent.isChroniclable(SubmissionEvent.KIND))
        assertFalse(ChronicleEvent.isChroniclable(ChronicleEvent.KIND))
        assertFalse(ChronicleEvent.isChroniclable(ChronicleRequestEvent.KIND))
        FrostSigningEvents.ALL.forEach {
            assertFalse(ChronicleEvent.isChroniclable(it), "frost kind $it")
        }
    }

    @Test
    fun `the contributor lists are left out while nothing applies them`() {
        // Not an oversight. ChatMessage.applyInnerEvent has no arm that writes a
        // row for any of these, so chronicling them would cost bytes and restore
        // nothing. They belong in the list on the day that changes.
        assertFalse(ChronicleEvent.isChroniclable(TranslationArtifactVersionContributorListEvent.KIND))
        assertFalse(ChronicleEvent.isChroniclable(TranslationChapterContributorListEvent.KIND))
        assertFalse(ChronicleEvent.isChroniclable(TranslationContributorListEvent.KIND))
    }

    // ---- Dependency order ------------------------------------------------

    @Test
    fun `the order is the foreign keys not the kind numbers`() {
        fun rank(kind: Int) = assertNotNull(ChronicleEvent.applyRank(kind), "kind $kind has no rank")

        // Every one of these is a foreign key in Room, so a chronicle applied the
        // other way round is a constraint violation rather than a wrong answer.
        assertTrue(rank(DialectEvent.KIND) < rank(ArtifactEvent.KIND))
        assertTrue(rank(ArtifactEvent.KIND) < rank(ArtifactVersionEvent.KIND))
        assertTrue(rank(ArtifactVersionEvent.KIND) < rank(ChapterEvent.KIND))
        assertTrue(rank(ChapterEvent.KIND) < rank(ChunkEvent.KIND))
        assertTrue(rank(ArtifactVersionEvent.KIND) < rank(TranslationArtifactVersionEvent.KIND))
        assertTrue(rank(DialectEvent.KIND) < rank(TranslationArtifactVersionEvent.KIND))
        assertTrue(rank(TranslationArtifactVersionEvent.KIND) < rank(TranslationChapterEvent.KIND))
        assertTrue(rank(ChapterEvent.KIND) < rank(TranslationChapterEvent.KIND))

        // A translated chunk hangs off both sides at once -- the source chunk it
        // translates and the translation chapter it sits in -- so it is last.
        assertTrue(rank(ChunkEvent.KIND) < rank(TranslationChunkEvent.KIND))
        assertTrue(rank(TranslationChapterEvent.KIND) < rank(TranslationChunkEvent.KIND))

        // And the numbers really do disagree with the order, which is why this is
        // a list rather than a sortedBy { kind }.
        assertTrue(DialectEvent.KIND > ArtifactEvent.KIND)
        assertTrue(rank(DialectEvent.KIND) < rank(ArtifactEvent.KIND))
    }

    @Test
    fun `sorting a page puts what is referenced before what refers to it`() {
        val jumbled = listOf(
            payload(TranslationChunkEvent.KIND),
            payload(TranslationChapterEvent.KIND),
            payload(ChunkEvent.KIND),
            payload(DialectEvent.KIND),
            payload(TranslationArtifactVersionEvent.KIND),
            payload(ChapterEvent.KIND),
            payload(ArtifactVersionEvent.KIND),
            payload(ArtifactEvent.KIND),
        )

        assertEquals(
            listOf(
                DialectEvent.KIND,
                ArtifactEvent.KIND,
                ArtifactVersionEvent.KIND,
                ChapterEvent.KIND,
                ChunkEvent.KIND,
                TranslationArtifactVersionEvent.KIND,
                TranslationChapterEvent.KIND,
                TranslationChunkEvent.KIND,
            ),
            ChronicleEvent.inApplyOrder(jumbled).map { it.kind }
        )
    }

    @Test
    fun `two of a kind keep the order they were handed in`() {
        // Nothing orders two chapters of one version, and keeping the caller's
        // order is one less thing that differs between two members assembling the
        // same chronicle.
        val chapters = listOf(
            payload(ChapterEvent.KIND, "one", id = "1".repeat(64)),
            payload(ChapterEvent.KIND, "two", id = "2".repeat(64)),
            payload(ChapterEvent.KIND, "three", id = "3".repeat(64)),
        )

        assertEquals(
            listOf("one", "two", "three"),
            ChronicleEvent.inApplyOrder(chapters).map { it.content }
        )
    }

    // ---- The tags --------------------------------------------------------

    @Test
    fun `a page says which chronicle it is where in it and who for`() {
        val template = ChronicleEvent.build(
            payloads = listOf(payload(DialectEvent.KIND)),
            chronicleId = chronicleId,
            index = 2,
            count = 5,
            recipient = recipient,
            createdAt = 1_700_000_100L
        )

        val readBack = ChronicleEvent(
            id = "f".repeat(64),
            pubKey = "b".repeat(64),
            createdAt = template.createdAt,
            tags = template.tags,
            content = template.content,
            sig = ""
        )

        assertEquals(chronicleId, readBack.chronicleId())
        assertEquals(2, readBack.page()?.index)
        assertEquals(5, readBack.page()?.count)
        assertEquals(recipient, readBack.recipient())
        assertEquals(1, readBack.payloads()?.size)
    }

    @Test
    fun `a page outside its own chronicle is not a page`() {
        // Refused rather than clamped: the pair is how a receiver decides it has
        // everything, so a repaired one would let a truncated chronicle read as
        // complete.
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "5", "3")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "-1", "3")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0", "0")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0")))
        assertNull(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "one", "3")))

        assertEquals(0, ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0", "1"))?.index)
        assertTrue(ChroniclePageTag.parse(arrayOf(ChroniclePageTag.TAG_NAME, "0", "1"))!!.isOnlyPage())
    }

    @Test
    fun `a chronicle id and a recipient have to be there to be read`() {
        assertNull(ChronicleIdTag.parse(arrayOf(ChronicleIdTag.TAG_NAME)))
        assertNull(ChronicleIdTag.parse(arrayOf(ChronicleIdTag.TAG_NAME, " ")))
        assertNull(ChronicleIdTag.parse(arrayOf("other", chronicleId)))
        assertEquals(chronicleId, ChronicleIdTag.parse(arrayOf(ChronicleIdTag.TAG_NAME, chronicleId))?.chronicleId)

        assertNull(ChronicleRecipientTag.parse(arrayOf(ChronicleRecipientTag.TAG_NAME, "short")))
        assertEquals(
            recipient,
            ChronicleRecipientTag.parse(arrayOf(ChronicleRecipientTag.TAG_NAME, recipient))?.pubKey
        )
    }

    // ---- What build refuses to make --------------------------------------

    @Test
    fun `a page is not built over either cap or out of what it may not carry`() {
        // The outbound half of both checks. It is politeness rather than
        // security -- the inbound one is the boundary -- but a page nobody can
        // read back is better failed where it was made.
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(emptyList(), chronicleId, 0, 1, recipient)
        }
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = (0..ChronicleEvent.MAX_PAGE_EVENTS).map {
                    payload(DialectEvent.KIND, id = it.toString().padStart(64, '0'))
                },
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = listOf(payload(GroupKeyStateEvent.KIND)),
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChronicleEvent.build(
                payloads = listOf(
                    payload(DialectEvent.KIND, content = "x".repeat(ChronicleEvent.MAX_PAGE_BYTES))
                ),
                chronicleId = chronicleId,
                index = 0,
                count = 1,
                recipient = recipient
            )
        }
    }

    @Test
    fun `a request carries nothing but what it is`() {
        val template = ChronicleRequestEvent.build(createdAt = 1_700_000_100L)

        assertEquals(ChronicleRequestEvent.KIND, template.kind)
        assertEquals("", template.content)
        // The room is the envelope and the asker is the MLS sender, so there is
        // nothing left for the event itself to say.
        assertEquals(1, template.tags.size)
        assertEquals("alt", template.tags[0][0])
    }
}
