package to.curare.compose.nostr.frost

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.serialization.json.jsonArray
import to.curare.compose.network.serialization.CommonJson
import to.curare.compose.nostr.frost.tags.FrostKeyTag
import to.curare.compose.nostr.frost.tags.FrostSessionIdTag
import to.curare.compose.nostr.frost.tags.FrostSignerIdsTag

/**
 * The nostr kinds a FROST signing session is carried on.
 *
 * These are **rumor** kinds: they only ever exist inside an encryption a relay
 * cannot open, so no relay sees them and the replaceable semantics normally
 * implied by the 3xxxx range never apply. Almost always that is a Marmot group
 * event -- MLS-encrypted to the group and then wrapped again under the group's
 * exporter secret. The exception is a group's first session, which agrees what
 * its admin room will sign with *before* that room exists and so runs in the
 * NIP-17 room its ceremony ran in, on gift wraps. See
 * `GroupKeyStateManager.propose`.
 *
 * Who talks to whom, in order:
 *
 * ```
 *  proposer --[ 30320 proposal   ]-> everyone   the unsigned event to sign
 *  signer   --[ 30321 nonce      ]-> everyone   this device's public nonce
 *  proposer --[ 30322 signer set ]-> everyone   who is signing, and their aggregated nonce
 *  signer   --[ 30323 partial    ]-> everyone   this device's partial signature
 *  proposer --[ 30324 signature  ]-> everyone   the finished 64-byte signature
 *  anyone   --[ 30325 failure    ]-> everyone   abandon + blame
 * ```
 *
 * [GroupKeyStateEvent] sits just past them on 30326. It is not part of a
 * session -- it is the standing fact a session is opened against, saying which
 * key the room signs with -- so it is deliberately outside [ALL], which is what
 * the inbound path dispatches a session message on.
 *
 * ### Why 3032x
 *
 * These share the inner-event space with the nip30303 document kinds, which run
 * 30300 up to [to.curare.compose.nostr.nip30303.SubmissionEvent] at 30312 --
 * the same space, because both are Marmot inner events and both are dispatched
 * on kind by the same inbound path. Starting at 30320 leaves that family room to
 * grow into.
 *
 * The DKG's 30310-30316 look like a clash and are not, but the reason is
 * narrower than it used to be. The nip30303 kinds exist only inside Marmot inner
 * events and the DKG's only inside NIP-17 gift wraps, so the numbers overlap
 * where nothing reads across -- the DKG's proposal and host-key kinds sit on
 * 30310 and 30311 alongside two nip30303 kinds, and its round-1 kind is 30312,
 * alongside SubmissionEvent.
 *
 * These kinds are now the ones that *do* travel on both transports, which is
 * exactly why they must not overlap with either family. They do not. But that
 * separation was an accident of routing rather than a decision, and the next
 * family added should not rely on it.
 */
object FrostSigningEvents {
    /**
     * Opens a session. Content is the unsigned nostr event -- or, for a batch,
     * the array of them -- as JSON; the key to sign with is named in [FrostKeyTag].
     * See [encodeProposal] for why the two forms are not one.
     */
    val PROPOSAL: Kind = 30320

    /** A signer's `IndividualNonce`, hex encoded. */
    val NONCE: Kind = 30321

    /**
     * The coordinator's chosen signers, in [FrostSignerIdsTag], with their
     * `AggregatedNonce` as the content, hex encoded.
     */
    val SIGNER_SET: Kind = 30322

    /** A signer's 32-byte partial signature, hex encoded. */
    val PARTIAL_SIGNATURE: Kind = 30323

    /** The finished 64-byte BIP-340 signature over the event id, hex encoded. */
    val SIGNATURE: Kind = 30324

    /** Session abandoned. Content is the reason, for showing to the group. */
    val FAILURE: Kind = 30325

    /** Every kind above, for filtering inbound payloads in one check. */
    val ALL: Set<Kind> = setOf(
        PROPOSAL,
        NONCE,
        SIGNER_SET,
        PARTIAL_SIGNATURE,
        SIGNATURE,
        FAILURE
    )

    fun isFrostSigningKind(kind: Kind): Boolean = kind in ALL

    /**
     * Tags for a signing message. The session id is on every kind so a message
     * from an abandoned attempt can be dropped rather than mixed in.
     */
    fun assembleTags(
        sessionId: String,
        dkgSessionId: String? = null,
        signerIds: List<Int>? = null
    ): Array<Array<String>> = buildList {
        add(FrostSessionIdTag.assemble(sessionId))
        dkgSessionId?.let { add(FrostKeyTag.assemble(it)) }
        signerIds?.let { add(FrostSignerIdsTag.assemble(it)) }
    }.toTypedArray()

    fun parseSessionId(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(FrostSessionIdTag::parse)?.sessionId

    fun parseKey(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(FrostKeyTag::parse)?.dkgSessionId

    fun parseSignerIds(tags: Array<Array<String>>): List<Int>? =
        tags.firstNotNullOfOrNull(FrostSignerIdsTag::parse)?.signerIds

    /**
     * The unsigned events of a proposal, as its content.
     *
     * A batch of one serialises as the bare event object it always did, and only
     * a genuine batch becomes an array. That is not tidiness. A build predating
     * batching reads an array with `Event.fromJsonOrNull`, gets null, and drops
     * the proposal -- so an old device refuses a batch outright rather than
     * signing part of one, while single signing keeps working right through a
     * mixed-version rollout. Emitting an array unconditionally would break every
     * one-event session for those devices and buy nothing.
     *
     * An empty list has no honest encoding and is not one, so callers do not
     * produce it and [decodeProposal] does not accept it.
     */
    fun encodeProposal(events: List<Event>): String =
        events.singleOrNull()?.toJson()
            ?: events.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toJson() }

    /**
     * The other half. Both forms are accepted, permanently: proposals in the old
     * shape do not stop arriving just because this build stopped writing them.
     *
     * All-or-nothing. An array with one unreadable element is refused rather than
     * silently shortened, because the batch's length is what every later payload
     * is checked against -- a proposal that quietly lost an event would have every
     * signer's contribution rejected for being the wrong size, which is a stall
     * with no message to blame.
     */
    fun decodeProposal(content: String): List<Event>? {
        if (!content.trimStart().startsWith("[")) {
            return Event.fromJsonOrNull(content)?.let { listOf(it) }
        }

        val elements = runCatching { CommonJson.parseToJsonElement(content).jsonArray }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return elements.map { element -> Event.fromJsonOrNull(element.toString()) ?: return null }
    }
}
