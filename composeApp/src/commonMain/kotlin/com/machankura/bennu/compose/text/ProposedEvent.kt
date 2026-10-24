package com.machankura.bennu.compose.text

import com.machankura.bennu.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.bennu.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.bennu.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.nostr.wallet.MemberWalletCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.MemberWalletRequestEvent
import com.machankura.bennu.compose.managers.SharedKeyDerivation
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent

/**
 * An event a group is being asked to sign, said in words.
 *
 * Shown as the thing rather than as an event: a member deciding whether to sign
 * is deciding about a wallet or a subgroup, and "kind 30331" answers a question
 * nobody asked. The raw kind stays for anything not recognised, since
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

    /**
     * [nameOf] names a member by public key, for the one summary that is about a
     * person: a caller with profiles to hand passes them, and one without gets
     * the summary without a name rather than a wrong one.
     */
    fun summarize(event: Event, nameOf: (HexKey) -> String? = { null }): Summary = when (event.kind) {
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
                // How long this group's own leaf waits, which its coordinator
                // chose; every inherited one keeps its own. docs/guardian-delay.md.
                SubgroupBirthCertificateEvent.parseGuardians(event.tags)
                    ?.firstOrNull()
                    ?.let { "this group after ${BlockDuration.describe(it.delay)}" },
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

        // The group agreeing to guard one member's wallet. What a signer weighs
        // is whose wallet, and what the group can do with it: take any coin that
        // has sat for its leaf's delay, alongside every group above it after
        // theirs, while only the member can spend before then. The keys are their device's to check.
        // The group deciding whether it guards its members' wallets, and at what
        // delay. A signer weighs the delay, and that it reaches only wallets
        // asked for from now on; closing leaves every guarded wallet guarded.
        // docs/member-wallet-delay.md.
        MemberWalletPolicyEvent.KIND -> Summary(
            label = "Members' guarded wallets",
            detail = when (val policy = MemberWalletPolicyEvent.read(event)) {
                is MemberWalletPolicyEvent.Policy.Open ->
                    "this group after ${BlockDuration.describe(policy.delay)} · for wallets asked for from now on"
                is MemberWalletPolicyEvent.Policy.Closed ->
                    "no new ones · wallets already guarded stay guarded"
                null -> "A policy this device cannot read"
            }
        )

        MemberWalletCertificateEvent.KIND -> Summary(
            label = "Guard a member's wallet",
            detail = MemberWalletCertificateEvent.requestOf(event)
                ?.let { MemberWalletRequestEvent.read(it) }
                ?.let { reading ->
                    // Each guardian's own delay, nearest first: the group's own
                    // leaf, and every group above it at the delay it chose.
                    // docs/guardian-delay.md.
                    val delays = reading.guardians.map { BlockDuration.describe(it.delay) }
                    val reach = when (delays.size) {
                        1 -> "this group can take its coins after ${delays[0]}"
                        2 -> "this group can take its coins after ${delays[0]}, and the one above it after ${delays[1]}"
                        else -> "this group can take its coins after ${delays[0]}, and the ${delays.size - 1} above it after ${delays.drop(1).joinToString(", ")}"
                    }
                    listOfNotNull(
                        nameOf(reading.memberPublicKey)?.let { "For @$it" },
                        reading.label,
                        "on ${GroupWalletStatementEvent.chainName(reading.chain)}",
                        reach,
                        "only the member can spend from it before then",
                    ).joinToString(" · ")
                }
                ?: "A member wallet this device cannot read"
        )

        else -> Summary(label = "Event of kind ${event.kind}", detail = event.content)
    }
}
