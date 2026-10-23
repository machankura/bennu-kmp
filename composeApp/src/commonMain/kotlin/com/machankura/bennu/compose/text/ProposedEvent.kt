package com.machankura.bennu.compose.text

import com.machankura.bennu.compose.nostr.lightning.GroupLightningOperatorStatementEvent
import com.machankura.bennu.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.machankura.bennu.compose.managers.SharedKeyDerivation
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.bennu.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.bennu.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.bennu.compose.nostr.nip30303.ArtifactEvent
import com.machankura.bennu.compose.nostr.nip30303.ArtifactVersionEvent
import com.machankura.bennu.compose.nostr.nip30303.ChapterEvent
import com.machankura.bennu.compose.nostr.nip30303.DialectEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationArtifactVersionEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationChapterEvent
import com.machankura.bennu.compose.nostr.nip30303.TranslationChunkEvent

/**
 * An event a group is being asked to sign, said in words.
 *
 * Shown as the thing rather than as an event: a member deciding whether to sign
 * is deciding about a dialect or an artifact, and "kind 30304" answers a
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
        DialectEvent.KIND -> Summary(
            label = "New dialect",
            detail = DialectEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).let { dialect ->
                listOfNotNull(dialect.name(), dialect.country(), dialect.language())
                    .joinToString(" · ")
            }
        )

        ArtifactEvent.KIND -> Summary(
            label = "New artifact",
            detail = ArtifactEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).let { artifact ->
                // The url is the substance of an artifact -- signing one is putting
                // the group's name to what it points at -- so it goes next to the
                // name rather than being left for the detail screen afterwards.
                listOfNotNull(event.content, artifact.versionLabel(), artifact.url())
                    .joinToString(" · ")
            }
        )

        // Signed alongside the artifact it belongs to rather than on its own,
        // so this is almost always read as the second line of a batch of two.
        // Named after the artifact rather than the label, because the label is
        // the whole of the content and would otherwise be said twice.
        ArtifactVersionEvent.KIND -> Summary(
            label = "Version of the artifact",
            detail = event.content.ifBlank { "Unlabelled" }
        )

        ChapterEvent.KIND -> Summary(
            label = "New chapter",
            detail = ChapterEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).let { chapter ->
                // The text is the substance of a chapter -- signing one is putting
                // the group's name to what everybody will translate from -- but it
                // is a whole chapter, so its size stands in for it here. The chunk
                // count says how many of the batch's other items came out of this
                // text, which is the rest of what is being signed.
                val chunkCount = chapter.originalText()?.let { Markdown.splitParagraphs(it).size }

                listOfNotNull(
                    chapter.name(),
                    chapter.wordCount()?.let { "$it words" },
                    chunkCount?.let { "$it ${if (it == 1) "chunk" else "chunks"}" }
                ).joinToString(" · ")
            }
        )

        TranslationArtifactVersionEvent.KIND -> Summary(
            label = "New translation",
            detail = TranslationArtifactVersionEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).let { translation ->
                // Named after the dialect it is into, which is the whole of what is
                // being decided: everything else in the batch follows from it.
                listOfNotNull(translation.name(), translation.visibility(), translation.license())
                    .joinToString(" · ")
            }
        )

        TranslationChapterEvent.KIND -> Summary(
            label = "Chapter of the translation",
            detail = TranslationChapterEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).let { chapter ->
                // A translation chapter is a place for translated chunks to hang
                // off, so there is nothing in it to read -- only where in the work
                // it sits. Counted from one, the way the chapter list reads.
                chapter.index()?.let { "Chapter ${it + 1}" } ?: "Position unknown"
            }
        )

        TranslationChunkEvent.KIND -> Summary(
            label = "Translated passage",
            detail = TranslationChunkEvent(
                event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig
            ).let { chunk ->
                // The translation itself is the whole of what is being decided --
                // a member signing this is agreeing that these words say what the
                // original said -- so it is the detail rather than a count of it.
                // Where in the chapter it sits comes first, since that is what
                // says which passage to read it against.
                listOfNotNull(
                    chunk.index()?.let { "Passage ${it + 1}" },
                    event.content
                ).joinToString(" · ")
            }
        )

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
                    ?.let { "run by $it members" }
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

        // The group keeping a Lightning wallet. What a member agrees to is a key, a node and an LSP
        // holding money under a policy, so the summary names the network, the threshold and the cap
        // on what can be in flight at once.
        GroupLightningWalletStatementEvent.KIND -> Summary(
            label = "Open a Lightning wallet",
            detail = GroupLightningWalletStatementEvent.read(event.tags)?.let { statement ->
                "On ${GroupWalletStatementEvent.chainName(statement.chain)}, ${statement.threshold} of ${statement.participants.size} to sign, " +
                    "at most ${statement.policy.inFlightCapSat} sats in flight"
            } ?: "A Lightning wallet statement this device cannot read"
        )

        GroupLightningOperatorStatementEvent.KIND -> Summary(
            label = "Choose who runs the Lightning wallet",
            detail = GroupLightningOperatorStatementEvent.read(event.tags)?.let { statement ->
                statement.operator?.let { "Term ${statement.term}: ${it.take(8)} runs the node" } ?: "Term ${statement.term}: nobody runs the node"
            } ?: "An operator statement this device cannot read"
        )

        else -> Summary(label = "Event of kind ${event.kind}", detail = event.content)
    }
}
