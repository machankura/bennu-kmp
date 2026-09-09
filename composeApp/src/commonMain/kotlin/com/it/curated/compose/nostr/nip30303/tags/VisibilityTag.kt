package com.it.curated.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class VisibilityTag(
    val visibility: String,
) {
    fun toTagArray() = assemble(visibility = visibility)

    companion object {
        const val TAG_NAME = "visibility"

        fun parse(tag: Array<String>): VisibilityTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return VisibilityTag(visibility = tag[1])
        }

        fun assemble(visibility: String): Array<String> = arrayOf(TAG_NAME, visibility)

        fun assemble(visibilityTag: VisibilityTag) = assemble(visibility = visibilityTag.visibility)
    }
}
