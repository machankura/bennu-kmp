package com.it.curated.compose.nostr.chronicle

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.serialization.json.jsonArray
import com.it.curated.compose.network.serialization.CommonJson
import com.it.curated.compose.nostr.chronicle.tags.ChronicleIdTag
import com.it.curated.compose.nostr.chronicle.tags.ChroniclePageTag
import com.it.curated.compose.nostr.chronicle.tags.ChronicleRecipientTag
import com.it.curated.compose.nostr.nip30303.ArtifactEvent
import com.it.curated.compose.nostr.nip30303.ArtifactVersionEvent
import com.it.curated.compose.nostr.nip30303.ChapterEvent
import com.it.curated.compose.nostr.nip30303.ChunkEvent
import com.it.curated.compose.nostr.nip30303.DialectEvent
import com.it.curated.compose.nostr.nip30303.TranslationArtifactVersionEvent
import com.it.curated.compose.nostr.nip30303.TranslationChapterEvent
import com.it.curated.compose.nostr.nip30303.TranslationChunkEvent

/**
 * 30327
 *
 * A page of the group's signed record, sent to a member who does not have it.
 *
 * A member added after the work was done sees none of it and never will. MLS
 * gives them no history -- correctly, that is forward secrecy -- but the sharper
 * problem is that group-signed events never travel at all: `FrostSigningManager`
 * applies a finished signature locally on every device that took part, because
 * the outbound pipeline would re-author a rumor as its sender and strip the
 * group's signature off. A member who was not in the session has nothing to
 * derive the event from, and no later message carries it. This is that path.
 *
 * Each payload travels whole, keeping its own id, author and signature, so the
 * receiver checks it rather than believing it -- see [isSignedByRoom] on
 * `GroupKeyStateEvent`. The sender of a chronicle is therefore not trusted, which
 * is what lets any member answer.
 *
 * See docs/member-chronicle.md for the whole design, including the two things this
 * deliberately does not do: it never carries the chat, and it cannot make its
 * recipient able to *sign* anything.
 *
 * ### Why not a [com.it.curated.compose.nostr.nip30303.SubmissionEvent] each
 *
 * The envelope fits and the meaning does not. A submission is an *act* -- this
 * member is putting this event in front of this group -- and a chronicle asserts
 * nothing; it re-delivers what the group already agreed. On one kind a
 * four-hundred-event backfill is indistinguishable from four hundred new
 * submissions and every device has to guess which it is looking at. It would
 * also be one inner event and one kind:445 per payload, where a page is one, and
 * the submission arm of `ChatMessage.applyInnerEvent` files a chat line per
 * payload, which a chronicle must not.
 */
@Immutable
class ChronicleEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    /**
     * The signed events this page carries, or null when the content is not a
     * readable page.
     *
     * Nothing here is verified yet. Parsing says the framing is intact;
     * `ChronicleManager` is what asks whether the group signed each one, and it
     * asks per payload rather than per page.
     */
    fun payloads(): List<Event>? = decodePage(content)

    /** Which chronicle this page belongs to, or null if it names none. */
    fun chronicleId(): String? = tags.firstNotNullOfOrNull(ChronicleIdTag::parse)?.chronicleId

    /** Where this page sits in that chronicle, or null if it does not say. */
    fun page(): ChroniclePageTag? = tags.firstNotNullOfOrNull(ChroniclePageTag::parse)

    /** Who should act on this page. A hint -- see [ChronicleRecipientTag]. */
    fun recipient(): HexKey? = tags.firstNotNullOfOrNull(ChronicleRecipientTag::parse)?.pubKey

    companion object {
        /**
         * Sits past `GroupKeyStateEvent` at 30326, in the Marmot inner-event
         * space it shares with the nip30303 document kinds and the FROST signing
         * family.
         *
         * 30313 was free next to the document kinds and is not used, on
         * `FrostSigningEvents`' own advice: the DKG's 30310-30316 already overlap
         * that range and are told apart only by living in NIP-17 gift wraps
         * instead, which is "an accident of routing rather than a decision, and
         * the next family added should not rely on it." This is that next family.
         *
         * It is also the right neighbourhood on the merits. A chronicle is not a
         * document kind; it is a statement about the record, which is what a key
         * state is too.
         */
        const val KIND: Kind = 30327

        const val ALT_DESCRIPTION = "Group chronicle"

        /**
         * The most JSON one page may carry.
         *
         * A byte cap rather than a count alone, because the payloads vary by two
         * orders of magnitude -- a chunk is a paragraph, an artifact is a URL.
         * Sized so the finished kind:445 clears the tightest relay limits with
         * room for the MLS framing and the NIP-44 expansion that sit outside it;
         * measure it against a real one rather than trusting the arithmetic.
         */
        const val MAX_PAGE_BYTES = 64 * 1024

        /**
         * The most payloads one page may carry.
         *
         * The byte cap bounds the transport; this bounds the receiver's *work*,
         * and they are not the same limit -- 64 KB of one-line dialects is a lot
         * more signature verifies than 64 KB of chapters.
         *
         * **Sized under what the byte cap allows, or it would never fire.** An
         * event carries a 64-character id, a 64-character pubkey and a
         * 128-character signature before it says anything at all, so the floor is
         * around 370 bytes and [MAX_PAGE_BYTES] cannot hold much past 170 of
         * them. A count cap above that ceiling is a check that cannot fail --
         * which is how this was first written, and a test that asserts a page at
         * exactly the cap decodes is what caught it. At 128 both caps bind
         * something: the count stops a page of many small payloads, the bytes
         * stop a page of few large ones.
         *
         * The two therefore have to be re-sized together. Raising [MAX_PAGE_BYTES]
         * without raising this narrows the count cap's job; raising this without
         * raising that retires it.
         *
         * Larger than `FrostSigningManager.MAX_BATCH_SIZE`, deliberately: a batch
         * item costs nonce generation, a FROST session and a native sign, where
         * a chronicle payload costs a signature verify and an upsert.
         */
        const val MAX_PAGE_EVENTS = 128

        /**
         * The kinds a chronicle may carry, in the order they have to be applied.
         *
         * One list doing both jobs, because a separate allowlist is one more
         * thing that can disagree with the order it is applied in.
         *
         * **The order is Room's, not nostr's.** Every one of these has a foreign
         * key on the one before it, so a chronicle applied out of order is a
         * constraint violation rather than a wrong answer. Kind order is not
         * dependency order and never was -- a translation chapter (30308) hangs
         * off a translation artifact version (30306) which hangs off an artifact
         * version (30301) -- so this is a list rather than a `sortedBy { kind }`.
         *
         * **It is an allowlist first.** Verification admits an event to the apply
         * path on the strength of the group's signature, which makes every kind
         * the group has ever signed replayable by any member at any time. A
         * `GroupKeyStateEvent` is group-signed and passes verification perfectly,
         * so a chronicle carrying an old one is a validly signed statement about
         * what the room signs with, replayed by whoever kept a copy. Nothing but
         * this list stops it.
         *
         * ### What is missing from it, and why
         *
         * **Only kinds the group actually signs can be here**, because a chronicle
         * that cannot be verified is one that has to be believed. Six of the
         * thirteen nip30303 kinds reach a signing session; the rest travel as
         * rumors -- empty signature, member author, vouched for by the MLS frame
         * they arrived in and by nothing that survives leaving it.
         *
         * Left out for that reason:
         *
         *  - `TranslationEvent` (30311). Nothing builds one; the inbound arm
         *    exists and no producer does.
         *  - The contributor lists (30305, 30307, 30310). `applyInnerEvent` has
         *    no arm that writes a row for any of them, so chronicling them would
         *    cost bytes and restore nothing.
         *
         * Two kinds were on that list and are not any more, because the app
         * stopped leaving them unsigned. `ArtifactVersionEvent` (30301) used to be
         * derived from the signed artifact on arrival, and is now the second item
         * of the batch that carries it. `TranslationChunkEvent` (30309) -- the
         * translated text itself -- used to be submitted as its author's rumor,
         * and is now proposed to the group like everything else. Both are
         * therefore checkable, and a chronicle that left them out would hand a new
         * member the whole structure and none of the prose.
         */
        private val APPLY_ORDER: List<Kind> = listOf(
            DialectEvent.KIND,
            ArtifactEvent.KIND,
            ArtifactVersionEvent.KIND,
            ChapterEvent.KIND,
            ChunkEvent.KIND,
            TranslationArtifactVersionEvent.KIND,
            TranslationChapterEvent.KIND,
            TranslationChunkEvent.KIND,
        )

        /** Every kind a chronicle may carry. */
        val CHRONICLABLE_KINDS: Set<Kind> = APPLY_ORDER.toSet()

        /**
         * Where [kind] sits in dependency order, or null if a chronicle may not
         * carry it at all.
         */
        fun applyRank(kind: Kind): Int? = APPLY_ORDER.indexOf(kind).takeIf { it >= 0 }

        fun isChroniclable(kind: Kind): Boolean = kind in CHRONICLABLE_KINDS

        /**
         * [payloads] in dependency order, so a page applies front to back.
         *
         * Stable within a rank: two chapters of one version have no order between
         * them and keeping the caller's is one less thing that varies between two
         * members assembling the same chronicle.
         */
        fun inApplyOrder(payloads: List<Event>): List<Event> =
            payloads.sortedBy { applyRank(it.kind) ?: Int.MAX_VALUE }

        /**
         * One page's payloads as its content: a JSON array of whole events.
         *
         * Always an array, even for one. `FrostSigningEvents.encodeProposal` has
         * a bare-object form only because a build predating batches has to be
         * able to read a single proposal, and there is no such build here -- both
         * kinds are new. Do not copy that shape.
         */
        fun encodePage(events: List<Event>): String =
            events.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toJson() }

        /**
         * The other half, with both caps enforced.
         *
         * **All-or-nothing, and that is not in tension with dropping forged
         * payloads one at a time.** They are different questions. A page that
         * will not parse has lost its framing, so nothing in it can be trusted to
         * be what the sender wrote -- and a page silently shortened by one
         * element would leave a receiver believing it holds a complete chronicle
         * when the page count says so and the contents do not. A payload whose
         * signature does not verify is a well-framed page containing one bad
         * event, and costing its honest neighbours would let one forged payload
         * deny an entire chronicle.
         *
         * Both caps are checked here rather than at the call site, because this
         * is the boundary a remote party's page crosses. A chronicle is the second
         * place in this protocol where somebody else decides how much work this
         * device does; the first grew a cap on the way in for the same reason.
         */
        fun decodePage(content: String): List<Event>? {
            if (content.encodeToByteArray().size > MAX_PAGE_BYTES) return null
            if (!content.trimStart().startsWith("[")) return null

            val elements = runCatching { CommonJson.parseToJsonElement(content).jsonArray }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_PAGE_EVENTS }
                ?: return null

            return elements.map { element -> Event.fromJsonOrNull(element.toString()) ?: return null }
        }

        /**
         * A page of [payloads], page [index] of [count] in chronicle [chronicleId],
         * for [recipient].
         *
         * The caller sorts and pages; this only writes what it is handed. Both
         * caps are checked anyway, because a page built over either of them is a
         * page nobody can read back and it is better to fail where it was made.
         */
        fun build(
            payloads: List<Event>,
            chronicleId: String,
            index: Int,
            count: Int,
            recipient: HexKey,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<ChronicleEvent>.() -> Unit = {},
        ): EventTemplate<ChronicleEvent> {
            require(payloads.isNotEmpty()) { "A chronicle page carries at least one event" }
            require(payloads.size <= MAX_PAGE_EVENTS) {
                "A chronicle page carries at most $MAX_PAGE_EVENTS events, not ${payloads.size}"
            }
            payloads.forEach {
                require(isChroniclable(it.kind)) { "A chronicle may not carry kind ${it.kind}" }
            }

            val content = encodePage(payloads)
            require(content.encodeToByteArray().size <= MAX_PAGE_BYTES) {
                "A chronicle page carries at most $MAX_PAGE_BYTES bytes"
            }

            return eventTemplate<ChronicleEvent>(KIND, content, createdAt) {
                alt(ALT_DESCRIPTION)
                addUnique(ChronicleIdTag.assemble(chronicleId))
                addUnique(ChroniclePageTag.assemble(index, count))
                addUnique(ChronicleRecipientTag.assemble(recipient))
                initializer()
            }
        }
    }
}
