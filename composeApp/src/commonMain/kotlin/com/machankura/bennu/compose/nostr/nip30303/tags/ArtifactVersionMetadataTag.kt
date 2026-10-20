package com.machankura.bennu.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class ArtifactVersionMetadataTag(
    val versionLabel: String,
) {
    fun toTagArray() = assemble(
        versionLabel = versionLabel,
    )

    companion object {
        const val TAG_NAME = "artifactVersion"

        fun parse(tag: Array<String>): ArtifactVersionMetadataTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return ArtifactVersionMetadataTag(
                versionLabel = tag[1],
            )
        }

        fun assemble(
            versionLabel: String,
        ): Array<String> = arrayOf(
            TAG_NAME,
            versionLabel
        )

        fun assemble(artifactVersionMetadataTag: ArtifactVersionMetadataTag) = assemble(
            versionLabel = artifactVersionMetadataTag.versionLabel,
        )
    }
}