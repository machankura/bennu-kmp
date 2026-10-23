package com.machankura.bennu.compose.text

import com.machankura.bennu.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.bennu.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.machankura.bennu.compose.managers.SharedKeyDerivation
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent

/**
 * An event a group is being asked to sign, said in words.
 *
 * Shown as the thing rather than as an event: a member deciding whether to sign
 * is deciding about a profile or a relay list, and "kind 10002" answers a
 * question nobody asked. The raw kind stays for anything not recognised, since
 * refusing to describe an event is better than describing it wrongly.
 *
 * Not a composable, and deliberately: the same words appear on the signing
 * screen and in the room's list of proposals, and two copies of this mapping
 * would drift into two different names for one thing.
 */
object ProposedEvent {
    /**
     * [label] is what kind of thing it is, [detail] which one. Both are shown
     * together, so neither repeats the other.
     */
    data class Summary(val label: String, val detail: String)

    fun summarize(event: Event): Summary = when (event.kind) {
        // The one thing a group signs that is about the group rather than about
        // its work, and the only one a member sees before the room has done
        // anything. Shown as the path and the ceremony rather than as the key:
        // the key is the room's own id, which the member is already looking at.
        GroupKeyStateEvent.KIND -> Summary(
            label = "This group's shared key",
            detail = listOfNotNull(
                GroupKeyStateEvent.parsePath(event.tags)?.let(SharedKeyDerivation::formatPath),
                GroupKeyStateEvent.parseDkgSessionId(event.tags)?.take(12)
            ).joinToString(" · ")
        )

        // The other thing a group signs that is about groups rather than about
        // work, and the only one where the *proposal* is the last chance to
        // check. A parent admin approving this is putting the group's signature
        // to a room id produced by a ceremony most of them were not in, so the
        // summary is who it is for and what it is called -- the two things they
        // can actually weigh. The id is shown by the screen, and their device has
        // already checked it derives from the key the certificate names.
        SubgroupBirthCertificateEvent.KIND -> Summary(
            label = "A new subgroup of this group",
            detail = listOfNotNull(
                SubgroupBirthCertificateEvent.parseName(event.tags),
                SubgroupBirthCertificateEvent.parseAdminPublicKeys(event.tags)
                    .size
                    .takeIf { it > 0 }
                    ?.let { "run by $it members" },
                // The promise this certificate makes on the group's behalf, said
                // as one: a guardian key is this group, and every group above it,
                // able to spend from the subgroup's wallet if it opens a guarded one.
                SubgroupBirthCertificateEvent.parseGuardians(event.tags)
                    ?.size
                    ?.takeIf { it > 0 }
                    ?.let { count ->
                        when (count) {
                            1 -> "this group can spend from its wallet"
                            2 -> "this group and the one above it can spend from its wallet"
                            else -> "this group and the ${count - 1} above it can spend from its wallet"
                        }
                    },
                // The decision this certificate makes for the child, said in both
                // directions: an absent line is not a decision anybody can be said
                // to have read. An older certificate decides nothing and says so
                // by saying nothing, as it always has.
                when (SubgroupBirthCertificateEvent.parseWalletPolicy(event.tags)) {
                    WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED) -> "its wallet must be guarded"
                    WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED) -> "its wallet will not be guarded"
                    else -> null
                }
            ).joinToString(" · ")
        )

        // The group's own profile. Named by what the group would be called and
        // what it would say about itself, which is the whole of what is being
        // decided; the JSON in the content is the one thing a signer should not
        // have to read to find that out.
        MetadataEvent.KIND -> Summary(
            label = "Nostr profile",
            detail = MetadataEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).contactMetaData()?.let { metadata ->
                listOfNotNull(
                    metadata.anyName()?.takeIf { it.isNotBlank() },
                    metadata.about?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifEmpty { "An empty profile" }
            } ?: "Unreadable profile"
        )

        // A post in the group's name. The words are what is being decided, so
        // they are the detail rather than a count of them -- the same call the
        // translated-passage arm makes.
        TextNoteEvent.KIND -> Summary(label = "Post", detail = event.content)

        // One of the group's relay lists, shown as hosts: every entry in one
        // starts wss://, so the scheme is the part that never tells two apart.
        // An empty one is a list the group is withdrawing, and says so.
        in GroupRelaySet.KINDS -> {
            val set = GroupRelaySet.entries.first { it.kind == event.kind }

            Summary(
                label = "${set.name} relay list",
                detail = set.parse(event.tags).joinToString { it.displayUrl() }.ifEmpty { "No relays" }
            )
        }

        // The group opening a wallet. What a member is agreeing to is that the
        // group keeps coins here, so the summary names the network -- the one
        // thing that changes what the coins are worth -- and the account, and
        // not the xpub, which is a derivation of the room's key nobody can check
        // by eye.
        GroupWalletStatementEvent.KIND -> Summary(
            label = "Open a group onchain wallet",
            detail = GroupWalletStatementEvent.read(event.tags, event.content)?.let { statement ->
                "On ${GroupWalletStatementEvent.chainName(statement.chain)}, account ${statement.account}"
            } ?: "A wallet statement this device cannot read"
        )

        else -> Summary(label = "Event of kind ${event.kind}", detail = event.content)
    }
}
