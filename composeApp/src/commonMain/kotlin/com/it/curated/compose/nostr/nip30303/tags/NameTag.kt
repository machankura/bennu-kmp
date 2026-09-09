package com.it.curated.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class NameTag(
    val name: String,
) {
    fun toTagArray() = assemble(name = name)

    companion object {
        const val TAG_NAME = "name"

        fun parse(tag: Array<String>): NameTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return NameTag(name = tag[1])
        }

        fun assemble(name: String): Array<String> = arrayOf(TAG_NAME, name)

        fun assemble(nameTag: NameTag) = assemble(name = nameTag.name)
    }
}
