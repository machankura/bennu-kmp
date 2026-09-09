package com.it.curated.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class LanguageTag(
    val language: String,
) {
    fun toTagArray() = assemble(language = language)

    companion object {
        const val TAG_NAME = "language"

        fun parse(tag: Array<String>): LanguageTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return LanguageTag(language = tag[1])
        }

        fun assemble(language: String): Array<String> = arrayOf(TAG_NAME, language)

        fun assemble(languageTag: LanguageTag) = assemble(language = languageTag.language)
    }
}
