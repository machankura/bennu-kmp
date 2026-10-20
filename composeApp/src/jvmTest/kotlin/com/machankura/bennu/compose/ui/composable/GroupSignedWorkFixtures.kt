package com.machankura.bennu.compose.ui.composable

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.machankura.bennu.compose.database.model.ChatRoom
import com.machankura.bennu.compose.database.model.GroupKeyState
import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.bennu.compose.nostr.GroupCuratedEntry
import com.machankura.bennu.compose.nostr.GroupCuratedSchema
import com.machankura.bennu.compose.nostr.GroupNostrProfile
import com.machankura.bennu.compose.nostr.GroupPost
import com.machankura.bennu.compose.nostr.GroupRelay
import com.machankura.bennu.compose.nostr.GroupRelayList
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.nostr.curated.CuratedEntry
import com.machankura.bennu.compose.nostr.curated.CuratedEntryEvent
import com.machankura.bennu.compose.nostr.curated.CuratedField
import com.machankura.bennu.compose.nostr.curated.CuratedFieldType
import com.machankura.bennu.compose.nostr.curated.CuratedSchema
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaEvent
import com.machankura.bennu.compose.nostr.curated.CuratedSuggestionRef
import com.machankura.bennu.compose.nostr.curated.CuratedVisibility
import kotlin.time.Instant

/**
 * One group's signed work, for the six screens that show it: the group's own,
 * where a row names each kind, and the five behind those rows.
 *
 * Everything here is built rather than read out of a signed event. The reading
 * checks a real signature, and these tests are about the screens rather than
 * about that -- so nothing here verifies, and none of the keys derive.
 */
internal object GroupSignedWorkFixtures {

    val chatRoomId = "d4c3b2a1".repeat(8)

    val activeUserPublicKey = "a".repeat(64)

    val suggester = "eebb74ab6bfb8485722ab1d4acee856eee96f17a9e1721f9c7cc63376c40b860"

    /**
     * The room, as this device names it.
     *
     * The subject is deliberately not the profile's name: the room's subject is
     * this device's name for it and the profile is the group's own, and a test
     * that used one string for both could not tell them apart.
     */
    fun localChatRoom(mlsGroupState: String? = "state") = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = activeUserPublicKey,
            subject = "Translation room",
            description = "A group translating hard books.",
            initialGiftWrapPayloadId = "sdfaer",
            mlsGroupState = mlsGroupState
        )
    )

    /**
     * A key state, so the signing key row the identity block sits under is on
     * the group's screen to be measured against. Not a real key: nothing here
     * derives.
     */
    val groupKeyState = GroupKeyState(
        chatRoomId = chatRoomId,
        dkgSessionId = "c".repeat(64),
        thresholdPublicKey = "02".padEnd(66, 'a'),
        derivationPath = "m/9420/0/0",
        announcedBy = chatRoomId,
        announcedAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    val profile = GroupNostrProfile(
        signedEvent = GroupSignedEvent(
            id = "e".repeat(64),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = MetadataEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
        metadata = UserMetadata().apply {
            displayName = "Translation collective"
            about = "A group translating hard books into Sesotho."
            nip05 = "group@example.com"
        }
    )

    /**
     * Two agreed sets and two absences, which is the only fixture that exercises
     * the three things a relay row can say: a list, "not set yet", and "no
     * relays".
     */
    fun signedRelayLists(): List<GroupRelayList> = GroupRelaySet.entries.map { set ->
        when (set) {
            GroupRelaySet.General -> GroupRelayList(
                set = set,
                signedEvent = relaySignedEvent(set),
                relays = listOf(GroupRelay(RelayUrlNormalizer.normalize("wss://relay.one.example")))
            )
            // Agreed and empty: the group withdrew its list rather than never
            // having had one.
            GroupRelaySet.Blocked -> GroupRelayList(
                set = set,
                signedEvent = relaySignedEvent(set),
                relays = emptyList()
            )
            else -> GroupRelayList(set = set, signedEvent = null, relays = emptyList())
        }
    }

    fun relaySignedEvent(set: GroupRelaySet) = GroupSignedEvent(
        id = "a" + set.kind.toString().padStart(5, '0') + "0".repeat(58),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = set.kind,
        tags = emptyArray(),
        content = "",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    fun post(content: String, createdAt: Long) = GroupPost(
        signedEvent = GroupSignedEvent(
            id = createdAt.toString().padStart(64, 'b'),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = TextNoteEvent.KIND,
            tags = emptyArray(),
            content = content,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt)
        )
    )

    /** A list the group curates, as the schema it signed for it. */
    fun schema(identifier: String, name: String, createdAt: Long) = GroupCuratedSchema(
        signedEvent = GroupSignedEvent(
            id = createdAt.toString().padStart(64, 'c'),
            chatRoomId = chatRoomId,
            publicKey = chatRoomId,
            kind = CuratedSchemaEvent.KIND,
            tags = emptyArray(),
            content = "",
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(createdAt)
        ),
        schema = CuratedSchema(
            identifier = identifier,
            title = "$name suggestion",
            name = name,
            description = "Things the group would translate, with a link to each.",
            visibility = CuratedVisibility.Public,
            fields = listOf(CuratedField.IDENTIFIER, CuratedField.TITLE),
            relays = listOf("wss://relay.one.example")
        )
    )

    /**
     * An entry the group accepted into the films list. The list's schema has a
     * year, so the card has a glance to give.
     */
    fun entry(
        title: String,
        createdAt: Long,
        suggestedBy: String? = suggester
    ): GroupCuratedEntry {
        val list = schema("films", "Films worth translating", 1_700_000_000).let { films ->
            films.copy(
                schema = films.schema.copy(
                    fields = films.schema.fields + CuratedField("year", CuratedFieldType.Year, isRequired = false, label = "Year")
                )
            )
        }
        val identifier = title.lowercase().replace(' ', '-')

        return GroupCuratedEntry(
            signedEvent = GroupSignedEvent(
                id = createdAt.toString().padStart(64, 'd'),
                chatRoomId = chatRoomId,
                publicKey = chatRoomId,
                kind = CuratedEntryEvent.CANONICAL_KIND,
                // The root names the list, as a real entry's does: it is how
                // the reading matched the entry to its schema, and how the
                // broadcast finds the list's relays.
                tags = arrayOf(
                    arrayOf("a", list.coordinate(), "", "root"),
                    arrayOf("d", identifier),
                    arrayOf("title", title)
                ),
                content = "",
                signature = "f".repeat(128),
                createdAt = Instant.fromEpochSeconds(createdAt)
            ),
            list = list,
            entry = CuratedEntry(
                kind = CuratedEntryEvent.CANONICAL_KIND,
                id = createdAt.toString().padStart(64, 'd'),
                pubKey = chatRoomId,
                createdAt = createdAt,
                identifier = identifier,
                title = title,
                values = mapOf(
                    "identifier" to listOf(identifier),
                    "title" to listOf(title),
                    "year" to listOf("2014"),
                ),
                source = suggestedBy?.let {
                    CuratedSuggestionRef(id = "a".repeat(64), coordinate = "31888:$it:$identifier", pubKey = it)
                }
            )
        )
    }
}
