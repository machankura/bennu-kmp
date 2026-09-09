package com.it.curated.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class OriginalTextTag(
    val originalText: String,
) {
    fun toTagArray() = assemble(originalText = originalText)

    companion object {
        const val TAG_NAME = "original_text"

        fun parse(tag: Array<String>): OriginalTextTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return OriginalTextTag(originalText = tag[1])
        }

        fun assemble(originalText: String): Array<String> = arrayOf(TAG_NAME, originalText)

        fun assemble(originalTextTag: OriginalTextTag) = assemble(originalText = originalTextTag.originalText)
    }
}
