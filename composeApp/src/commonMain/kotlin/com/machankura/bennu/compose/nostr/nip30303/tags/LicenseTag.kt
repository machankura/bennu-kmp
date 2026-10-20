package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class LicenseTag(
    val license: String,
) {
    fun toTagArray() = assemble(license = license)

    companion object {
        const val TAG_NAME = "license"

        fun parse(tag: Array<String>): LicenseTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return LicenseTag(license = tag[1])
        }

        fun assemble(license: String): Array<String> = arrayOf(TAG_NAME, license)

        fun assemble(licenseTag: LicenseTag) = assemble(license = licenseTag.license)
    }
}
