package to.curare.compose.nostr

import to.curare.compose.database.model.GroupSignedEvent
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.isLocalHost
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.BlockedRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayInfo
import com.vitorpamplona.quartz.nip65RelayList.tags.AdvertisedRelayType
import kotlin.time.Instant
import com.vitorpamplona.quartz.nip51Lists.relayLists.tags.RelayTag as Nip51RelayTag
import com.vitorpamplona.quartz.nip17Dm.settings.tags.RelayTag as Nip17RelayTag

/**
 * Where a group lives on nostr, as the group itself has said.
 *
 * A Marmot room's id is the pubkey it signs as, so the group is an author like any
 * other and the same four relay lists apply to it: where it publishes and is read
 * from, where its messages arrive, where it can be searched for, and what it will
 * not talk to. Each is a replaceable event of its own kind, so each is agreed --
 * and can be missing -- separately.
 *
 * Read off [GroupSignedEvent] and gated on [GroupSignedEvent.verifies], for the
 * reasons `GroupNostrProfile` gives at length: a group-signed event is not a
 * `NostrEvent`, and only an event the room itself authored is the group speaking.
 *
 * ### Nothing here reaches a relay by being signed
 *
 * The lists say where the group's work *should* go; sending it there is a separate
 * act, taken by a member one event at a time from `BroadcastGroupSignedEventScreen`,
 * and the General list is what that screen fills its relay picker from. See
 * `GroupNostrProfile`, which carries the same caveat for the same reason.
 *
 * ### Public tags only, and not by preference
 *
 * NIP-51 puts a relay list's entries in the encrypted half by default -- a blocked
 * list especially, since who you refuse to talk to is nobody's business. A group
 * cannot use it. The encryption is NIP-44 to the author's own key, and there is no
 * ECDH for a FROST threshold key here: the group can sign and cannot decrypt. So
 * every list is written in public tags, which is a real difference from what a
 * person's client would write and is worth knowing before blocking anything.
 */
data class GroupRelayList(
    val set: GroupRelaySet,
    /**
     * The group's statement, or null for a set it has never agreed.
     *
     * Null and an empty [relays] are different states: never said, versus said and
     * said nothing. A group that signs an empty list has withdrawn the one it had.
     */
    val signedEvent: GroupSignedEvent?,
    val relays: List<GroupRelay>,
) {
    /** When the group signed it, or null for a set it has never agreed. */
    val signedAt: Instant? get() = signedEvent?.createdAt

    /** Whether the group has ever put its key to this set. */
    val isAgreed: Boolean get() = signedEvent != null

    companion object {
        /**
         * The reading of one signed event as [set], or null when it is not one.
         *
         * The same three refusals as `GroupNostrProfile.of`: the wrong kind, a
         * signature that is not [chatRoomId]'s, or tags that carry no relay.
         */
        fun of(
            signedEvent: GroupSignedEvent,
            chatRoomId: String,
            set: GroupRelaySet,
        ): GroupRelayList? {
            if (signedEvent.kind != set.kind) return null
            if (!signedEvent.verifies()) return null

            return GroupRelayList(
                set = set,
                signedEvent = signedEvent,
                relays = set.parse(signedEvent.tags),
            )
        }

        /**
         * The newest list [chatRoomId] signed for [set] among [signedEvents].
         *
         * Never null: a set the group has never agreed reads back as an unsigned,
         * empty list rather than as an absence, so a caller always has a row per
         * set to show and [isAgreed] is what separates the two.
         *
         * Newest by the group's own timestamp with the id breaking a tie, for the
         * reason `GroupNostrProfile.newestAmong` gives: these are replaceable, and
         * two devices reading the same events in different orders have to agree.
         */
        fun newestAmong(
            signedEvents: List<GroupSignedEvent>,
            chatRoomId: String,
            set: GroupRelaySet,
        ): GroupRelayList =
            signedEvents
                .asSequence()
                .mapNotNull { of(it, chatRoomId, set) }
                .maxWithOrNull(compareBy({ it.signedAt }, { it.signedEvent?.id }))
                ?: GroupRelayList(set = set, signedEvent = null, relays = emptyList())

        /** Every set, in the order the editor shows them. */
        fun allAmong(
            signedEvents: List<GroupSignedEvent>,
            chatRoomId: String,
        ): List<GroupRelayList> =
            GroupRelaySet.entries.map { newestAmong(signedEvents, chatRoomId, it) }
    }
}

/**
 * One relay in one of a group's lists.
 *
 * [read] and [write] mean something only in [GroupRelaySet.General], which is the
 * one list NIP-65 gives markers to; everywhere else a relay is simply in the list
 * and both are true. They are kept on the shared type rather than split into two
 * so that the editor has one row shape and one add path.
 *
 * **Neither is not a state.** NIP-65 writes a bare `r` tag for both, `read` for
 * read-only and `write` for write-only, and has nothing at all for a relay that is
 * neither -- a relay you will not read from and will not write to is one you have
 * removed. The editor enforces that rather than this dropping it silently.
 */
data class GroupRelay(
    val url: NormalizedRelayUrl,
    val read: Boolean = true,
    val write: Boolean = true,
) {
    /** The host, for a list that is read rather than copied. */
    fun displayUrl(): String = url.displayUrl()
}

/**
 * The relay lists a group keeps, and what each one is for.
 *
 * The four a person's client keeps, because a group is an author like any other and
 * these are the four questions the network asks about one.
 *
 * **Key packages (MIP-00, kind 10051) are deliberately not here**, though this app
 * writes one for every person. A key package is a device's offer to be added to an
 * MLS group, and a group has no device and joins nothing -- so a group advertising
 * where its key packages live would be pointing at a place that will always be
 * empty, which is worse than saying nothing. See `MarmotKeyPackage`, which is keyed
 * by member.
 *
 * **Relay feeds (kind 10012) are not here either**, for the harder reason: the list
 * lives in the encrypted half and a threshold key cannot decrypt. See
 * [GroupRelayList]'s note on public tags.
 */
enum class GroupRelaySet(
    val kind: Kind,
    /** Whether NIP-65's read and write markers apply, which is General alone. */
    val hasReadWriteMarkers: Boolean = false,
) {
    /**
     * NIP-65: where the group publishes and where a reader should look for it.
     *
     * The one that matters most and the only one with markers. Everything the group
     * signs is addressed to whoever wants to read it, and this is the answer to
     * where.
     */
    General(AdvertisedRelayListEvent.KIND, hasReadWriteMarkers = true),

    /** NIP-17: where a message addressed to the group's key should be sent. */
    Messages(ChatMessageRelayListEvent.KIND),

    /** NIP-50: the relays the group would have a search of its work run against. */
    Search(SearchRelayListEvent.KIND),

    /** NIP-51: relays the group will not talk to, whatever else says otherwise. */
    Blocked(BlockedRelayListEvent.KIND),
    ;

    /**
     * What a group with nothing agreed starts the editor at.
     *
     * The app's own relay, which is where everything else this build publishes and
     * reads already goes -- see `Relays.ephemeral`. A group seeded with nothing
     * would be a group whose first proposal is an empty list, which says less than
     * having never said anything at all.
     *
     * Blocked is the exception and starts empty on purpose: a default there is a
     * refusal to talk to somebody that nobody in the group chose.
     */
    fun defaults(): List<GroupRelay> = when (this) {
        Blocked -> emptyList()
        else -> listOf(GroupRelay(Relays.ephemeral))
    }

    /** The relays out of a signed event's tags, in the order the group wrote them. */
    fun parse(tags: Array<Array<String>>): List<GroupRelay> = when (this) {
        General -> tags.mapNotNull(AdvertisedRelayInfo::parse).map {
            GroupRelay(
                url = it.relayUrl,
                read = it.type.isRead(),
                write = it.type.isWrite(),
            )
        }
        // Both nip51 lists and NIP-17's use a "relay" tag, and the two RelayTag
        // classes that parse it are different classes in different packages. Named
        // apart at the import so a reader can see which NIP each line is following.
        Messages -> tags.mapNotNull(Nip17RelayTag::parse).map { GroupRelay(it) }
        Search, Blocked -> tags.mapNotNull(Nip51RelayTag::parse).map { GroupRelay(it) }
    }.distinctBy { it.url }

    /**
     * The event to ask the group to sign for this set.
     *
     * Built from scratch rather than from the group's last one, which is the
     * opposite of what `GroupNostrProfile.template` does and for a reason: a
     * profile is a bag of independent fields where an unknown one is worth keeping,
     * and a relay list is one list where the whole point of signing a new one is to
     * replace it. Carrying a tag forward here would make a removal impossible.
     *
     * A relay that is neither read nor write is dropped rather than written, since
     * NIP-65 cannot express one -- the editor does not allow the state, and this is
     * the second line of that defence rather than the first.
     */
    fun template(relays: List<GroupRelay>): EventTemplate<*> {
        val tags = when (this) {
            General -> relays.mapNotNull { relay ->
                val type = when {
                    relay.read && relay.write -> AdvertisedRelayType.BOTH
                    relay.read -> AdvertisedRelayType.READ
                    relay.write -> AdvertisedRelayType.WRITE
                    else -> return@mapNotNull null
                }
                AdvertisedRelayInfo.assemble(relay.url, type)
            }
            Messages -> relays.map { Nip17RelayTag.assemble(it.url) }
            Search, Blocked -> relays.map { Nip51RelayTag.assemble(it.url) }
        }

        // Empty content throughout. For General and Messages that is all the kind
        // has; for the two nip51 lists it is where the encrypted half would go, and
        // a group has no encrypted half. See [GroupRelayList].
        return EventTemplate<Nothing>(
            createdAt = kotlin.time.Clock.System.now().epochSeconds,
            kind = kind,
            tags = tags.toTypedArray(),
            content = "",
        )
    }

    companion object {
        /**
         * The kinds a group's relay lists are written as.
         *
         * A set rather than a walk of [entries], for `ChatMessage.applyInnerEvent`,
         * whose dispatch is one `when` over an event's kind -- and a `when` branch
         * has to be a constant expression rather than a predicate over an enum.
         */
        val KINDS: Set<Kind> = entries.map { it.kind }.toSet()

        /**
         * [url] as a relay a group could actually be told to use, or null.
         *
         * Structural only, and deliberately narrow: `wss://` because a group's
         * signed list is read by strangers over the open network and `ws://` would
         * ask them to fetch it in the clear, and no loopback because a relay only
         * this device can reach is not something to put a quorum's signature on.
         * Wisp's `RelayConfig.isValidUrl` refuses the same three things.
         */
        fun relayUrlOrNull(url: String): NormalizedRelayUrl? {
            val trimmed = url.trim()
            if (!trimmed.startsWith("wss://", ignoreCase = true)) return null

            return RelayUrlNormalizer.normalizeOrNull(trimmed)?.takeUnless { it.isLocalHost() }
        }
    }
}
