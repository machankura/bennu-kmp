package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.tags.events.EventReference
import com.vitorpamplona.quartz.utils.arrayOfNotNull
import com.vitorpamplona.quartz.utils.ensure

class TranslationChapterIdTag(
    val ref: EventReference,
) {
    constructor(eventId: String, relayHint: NormalizedRelayUrl?, pubkey: String?) : this(
        EventReference(eventId, pubkey, relayHint)
    )

    companion object {
        const val TAG_NAME = "translationChapterIdTag"

        fun parse(tag: Array<String>): TranslationChapterIdTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }

            val relayHint = tag.getOrNull(2)?.let { RelayUrlNormalizer.normalizeOrNull(it) }

            return TranslationChapterIdTag(tag[1], relayHint, tag.getOrNull(3))
        }

        fun assemble(
            eventId: HexKey,
            relay: NormalizedRelayUrl? = null,
            pubkey: String? = null,
        ) = arrayOfNotNull(TAG_NAME, eventId, relay?.url, pubkey)

        fun assemble(ref: EventReference) = assemble(ref.eventId, ref.relayHint, ref.author)
    }
}