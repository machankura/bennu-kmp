package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class CountryTag(
    val country: String,
) {
    fun toTagArray() = assemble(country = country)

    companion object {
        const val TAG_NAME = "country"

        fun parse(tag: Array<String>): CountryTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return CountryTag(country = tag[1])
        }

        fun assemble(country: String): Array<String> = arrayOf(TAG_NAME, country)

        fun assemble(countryTag: CountryTag) = assemble(country = countryTag.country)
    }
}
