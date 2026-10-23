package com.machankura.bennu.compose.nostr.chronicle

import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent

/**
 * The kinds the chronicle tests chronicle, in the order they apply.
 *
 * The app's own list, [ChronicleEvent.KINDS], is empty, so the machinery is
 * exercised with this one instead. It is not a proposal for what the app should
 * carry -- see [ChronicleEvent.KINDS] for why each of these is left out there.
 *
 * Chosen for three properties:
 *
 *  - **each is a group-signed kind `ChatMessage.applyInnerEvent` has an arm
 *    for**, so a payload goes down the real apply path rather than falling
 *    through to "unsupported";
 *  - **none of those arms writes a row of its own or throws** -- the group's
 *    profile, relay lists and posts are read straight off `GroupSignedEvent`
 *    -- so what a receiver holds afterwards is exactly the `GroupSignedEvent`
 *    rows the chronicle recorded, and comparing those is comparing the work;
 *  - **the order disagrees with the kind numbers** (10002 before 0 before 1),
 *    so a test that the order is the list's cannot pass by sorting on kind.
 *
 * A key state and a birth certificate are deliberately not here: they are what
 * the allowlist has to refuse, so they are what the tests try to replay.
 */
internal val TEST_KINDS: ChronicleKinds = ChronicleKinds(
    listOf(
        AdvertisedRelayListEvent.KIND,
        MetadataEvent.KIND,
        TextNoteEvent.KIND,
    )
)
