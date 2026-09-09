package com.it.curated.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.utils.TimeUtils
import com.it.curated.compose.nostr.nip30303.tags.PayloadIdTag
import com.it.curated.compose.nostr.nip30303.tags.PayloadKindTag

/**
 * 30312
 *
 * An envelope that carries one nip30303 event into a group.
 *
 * Every other nip30303 kind describes a thing -- an artifact, a dialect, a
 * chapter, a translated chunk. A submission describes an act: *this member is
 * putting this event in front of this group*. The two are separate on purpose,
 * because they answer different questions and often have different answers.
 *
 * The payload travels whole, in [content], keeping its own id, author and
 * signature. Nothing is rewritten to make it look like the submitter's work.
 * That buys two things:
 *
 *  - A group can take in work written by somebody who is not in it. A
 *    translation lifted from a public chronicle, a chapter transcribed by an
 *    outside contributor, an artifact somebody published years ago -- an admin
 *    submits it and the group applies it, with the original author still named
 *    on the row.
 *  - Authorship stops being a claim the transport makes. Before this, being
 *    the sender of a group message *was* being the author of the event inside
 *    it, so the only events a group could hold were ones its own members had
 *    written under their own keys.
 *
 * A submission is not an endorsement and grants nothing: a payload's author is
 * whoever signed it, and who may submit is the group's business.
 */
@Immutable
class SubmissionEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {

    /**
     * The submitted event, parsed out of [content].
     *
     * Null when the content is not an event at all -- treat that as a
     * submission that cannot be applied, not as an empty one.
     */
    fun payload(): Event? = Event.fromJsonOrNull(content)

    fun payloadKind() = tags.firstNotNullOfOrNull(PayloadKindTag::parse)?.kind

    fun payloadIdReference() = tags.firstNotNullOfOrNull(PayloadIdTag::parse)?.ref
    fun payloadId() = payloadIdReference()?.eventId

    /** Who wrote the payload, which is not who sent this submission. */
    fun payloadAuthor() = payloadIdReference()?.author

    companion object {
        const val KIND = 30312
        const val ALT_DESCRIPTION = "Submission"

        fun build(
            payload: Event,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<SubmissionEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, payload.toJson(), createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(PayloadKindTag.assemble(payload.kind))
            addUnique(PayloadIdTag.assemble(eventId = payload.id, pubkey = payload.pubKey))
            initializer()
        }
    }
}
