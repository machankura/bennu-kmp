package to.curare.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Based on NIP-F4
 */
class ContributorTag(
    val pubKey: HexKey,
    val role: String? = null,
) {
    fun toTagArray() = assemble(pubKey, role)

    companion object {
        const val TAG_NAME = "p"

        const val ROLE_TRANSLATOR = "translator"
        const val ROLE_PROOF_READER = "proofreader"
        const val ROLE_EDITOR = "editor"

        private val KNOWN_ROLES = setOf(ROLE_TRANSLATOR, ROLE_PROOF_READER, ROLE_EDITOR)

        fun isTagged(tag: Array<String>) = tag.has(1) && tag[0] == TAG_NAME && tag[1].length == 64

        fun parse(tag: Array<String>): ContributorTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            ensure(tag[1].length == 64) { return null }
            // NIP-F4 overloads slot 2 as role — but only host/cohost/editor are spec-defined.
            // Anything else (notably a stale relay-hint URL copied from a kind:3 p-tag) gets
            // dropped so it doesn't render as 'Role: wss://relay…' under the author chip.
            val role = tag.getOrNull(2)?.takeIf { it in KNOWN_ROLES }
            return ContributorTag(tag[1], role)
        }

        fun assemble(
            pubKey: HexKey,
            role: String? = null,
        ): Array<String> =
            if (role.isNullOrEmpty()) {
                arrayOf(TAG_NAME, pubKey)
            } else {
                arrayOf(TAG_NAME, pubKey, role)
            }

        fun assemble(author: ContributorTag) = assemble(author.pubKey, author.role)

        fun assemble(authors: List<ContributorTag>) = authors.map { assemble(it) }
    }
}