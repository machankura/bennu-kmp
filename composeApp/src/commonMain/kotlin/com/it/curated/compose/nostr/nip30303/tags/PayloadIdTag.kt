package com.it.curated.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.events.EventReference
import com.vitorpamplona.quartz.utils.arrayOfNotNull
import com.vitorpamplona.quartz.utils.ensure

/**
 * The event a submission carries, named on the submission itself.
 *
 * The payload is already in the submission's content, so this tag is not how a
 * reader gets at it -- it is how they filter for it. The author slot matters
 * more than usual here: on a submission it names whoever wrote the payload,
 * who is not necessarily the member who submitted it.
 */
data class PayloadIdTag(
    val ref: EventReference,
) {
    constructor(eventId: String, relayHint: NormalizedRelayUrl?, pubkey: String?) : this(
        EventReference(eventId, pubkey, relayHint)
    )

    companion object {
        const val TAG_NAME = "payloadId"

        fun parse(tag: Array<String>): PayloadIdTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }

            val relayHint = tag.getOrNull(2)?.let { RelayUrlNormalizer.normalizeOrNull(it) }

            return PayloadIdTag(tag[1], relayHint, tag.getOrNull(3))
        }

        fun assemble(
            eventId: HexKey,
            relay: NormalizedRelayUrl? = null,
            pubkey: String? = null,
        ) = arrayOfNotNull(TAG_NAME, eventId, relay?.url, pubkey)

        fun assemble(ref: EventReference) = assemble(ref.eventId, ref.relayHint, ref.author)
    }
}
