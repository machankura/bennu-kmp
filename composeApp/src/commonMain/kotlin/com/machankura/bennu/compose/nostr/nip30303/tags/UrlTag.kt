package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class UrlTag(
    val url: String,
) {
    fun toTagArray() = assemble(url = url)

    companion object {
        const val TAG_NAME = "url"

        fun parse(tag: Array<String>): UrlTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return UrlTag(url = tag[1])
        }

        fun assemble(url: String): Array<String> = arrayOf(TAG_NAME, url)

        fun assemble(urlTag: UrlTag) = assemble(url = urlTag.url)
    }
}
