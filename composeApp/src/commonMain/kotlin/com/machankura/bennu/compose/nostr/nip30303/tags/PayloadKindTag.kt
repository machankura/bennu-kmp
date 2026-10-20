package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * The kind of the event a submission carries.
 *
 * Lets a client decide whether it can apply a submission without parsing the
 * payload JSON out of the content first.
 */
class PayloadKindTag(
    val kind: Int,
) {
    fun toTagArray() = assemble(
        kind = kind,
    )

    companion object {
        const val TAG_NAME = "payloadKind"

        fun parse(tag: Array<String>): PayloadKindTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val kind = tag[1].toIntOrNull() ?: return null
            return PayloadKindTag(
                kind = kind,
            )
        }

        fun assemble(
            kind: Int,
        ): Array<String> = arrayOf(
            TAG_NAME,
            kind.toString()
        )

        fun assemble(payloadKindTag: PayloadKindTag) = assemble(
            kind = payloadKindTag.kind,
        )
    }
}
