package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class IndexTag(
    val index: Int,
) {
    fun toTagArray() = assemble(
        index = index,
    )

    companion object {
        const val TAG_NAME = "index"

        fun parse(tag: Array<String>): IndexTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val index = tag[1].toIntOrNull() ?: return null
            return IndexTag(
                index = index,
            )
        }

        fun assemble(
            index: Int,
        ): Array<String> = arrayOf(
            TAG_NAME,
            index.toString()
        )

        fun assemble(indexTag: IndexTag) = assemble(
            index = indexTag.index,
        )
    }
}