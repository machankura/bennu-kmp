package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.it.curated.compose.database.model.ChatRoom
import com.it.curated.compose.database.model.GroupKeyState
import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.nostr.GroupNostrProfile
import com.it.curated.compose.nostr.GroupRelay
import com.it.curated.compose.nostr.GroupRelayList
import com.it.curated.compose.nostr.GroupRelaySet
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.MantraRepository
import com.it.curated.compose.repository.NostrRepository
import com.it.curated.compose.ui.composable.widgets.ProvideSnackbarHost
import com.it.curated.compose.ui.theme.CuratedTheme
import com.it.curated.compose.ui.view.state.ChatRoomDetailUIState
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Where the group's nostr profile sits, and when it is there at all.
 *
 * The section's contents need no test -- a name and a line of prose in a
 * `ListItem` -- but two things about it do, and neither is settled by reading the
 * screen top to bottom.
 *
 * **Its place in the order.** It goes under what the room *is* -- the key it signs
 * as and whose child it is -- and above what the room has *made*, because a
 * profile is part of the identity rather than part of the work. That ordering is
 * an intention that a later `item {}` inserted in the wrong place would silently
 * undo, and the screen is long enough that nobody would notice.
 *
 * **Its absence in a NIP-17 room.** A NIP-17 room's id is a conversation, not a
 * key it can sign as, so it has no nostr identity and never will. An empty section
 * there would promise something that is not coming, which is worse than saying
 * nothing.
 */
@OptIn(ExperimentalTestApi::class)
class GroupNostrProfileSectionJvmTest {

    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val profile = GroupNostrProfile(
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
        // Built rather than read out of the event: the reading checks a real
        // signature, and this test is about the screen rather than about that.
        metadata = UserMetadata().apply {
            displayName = "Translation collective"
            about = "A group translating hard books into Sesotho."
            nip05 = "group@example.com"
        }
    )

    @Test
    fun `the profile sits under the signing key and above the library`() = render(
        state(groupNostrProfile = profile)
    ) {
        val signingKey = onNodeWithText("Signing key").getBoundsInRoot().top
        val nostrProfile = onNodeWithText("Nostr profile").getBoundsInRoot().top
        val library = onNodeWithText("Library").getBoundsInRoot().top

        assertTrue(
            signingKey < nostrProfile,
            "the nostr profile section climbed above the signing key row"
        )
        assertTrue(
            nostrProfile < library,
            "the nostr profile section fell below the library"
        )

        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("group@example.com").assertIsDisplayed()
    }

    @Test
    fun `the relay lists sit under the profile and above the library`() = render(
        state(groupNostrProfile = profile, relayLists = signedRelayLists())
    ) {
        val nostrProfile = onNodeWithText("Nostr profile").getBoundsInRoot().top
        val relays = onNodeWithText("Relays").getBoundsInRoot().top
        val library = onNodeWithText("Library").getBoundsInRoot().top

        assertTrue(nostrProfile < relays, "the relay lists climbed above the profile")
        assertTrue(relays < library, "the relay lists fell below the library")

        // Every set gets a row whatever the group has said, and the two absences
        // read differently: one has never been agreed, the other was agreed empty.
        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("relay.one.example").assertIsDisplayed()
        onNodeWithText("Messages").assertIsDisplayed()
        onNodeWithText("Search").assertIsDisplayed()
        onNodeWithText("Blocked").assertIsDisplayed()
        // Two of them, because Messages and Search are both unagreed -- and the
        // count is the assertion: a row per set, not a row per set the group has
        // got round to.
        onAllNodesWithText("Not set yet").assertCountEquals(2)
        onNodeWithText("No relays in this list").assertIsDisplayed()
        onNodeWithText("Edit relays").assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key reads the relays and is offered no edit`() = render(
        state(
            groupNostrProfile = profile,
            relayLists = signedRelayLists(),
            canEditNostrProfile = false
        )
    ) {
        onNodeWithText("General").assertIsDisplayed()
        onNodeWithText("relay.one.example").assertIsDisplayed()
        onNodeWithText("Edit relays").assertDoesNotExist()
    }

    @Test
    fun `a group that has said nothing says so, and offers to say something`() = render(
        state(groupNostrProfile = null)
    ) {
        onNodeWithText("Nostr profile").assertIsDisplayed()
        onNodeWithText("This group hasn't said anything about itself on Nostr yet.")
            .assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key reads the profile and is offered no edit`() = render(
        state(groupNostrProfile = profile, canEditNostrProfile = false)
    ) {
        // The profile is worth reading whoever is looking; proposing one needs a
        // share, and `proposeSigningBatch` throws without one.
        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    @Test
    fun `a NIP-17 room has no nostr identity and so no section`() = render(
        state(groupNostrProfile = null, mlsGroupState = null, canEditNostrProfile = false)
    ) {
        onNodeWithText("Nostr profile").assertDoesNotExist()
        onNodeWithText("This group hasn't said anything about itself on Nostr yet.")
            .assertDoesNotExist()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()

        // The relay lists go with it: they describe a nostr identity this room
        // does not have.
        onNodeWithText("Relays").assertDoesNotExist()
        onNodeWithText("Edit relays").assertDoesNotExist()

        // The rest of the screen is untouched, so the section's absence is an
        // absence rather than a screen that failed to draw.
        onNodeWithText("Library").assertIsDisplayed()
    }

    /**
     * Two agreed sets and two absences, which is the only fixture that exercises
     * the three things a row can say: a list, "not set yet", and "no relays".
     */
    private fun signedRelayLists(): List<GroupRelayList> = GroupRelaySet.entries.map { set ->
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

    private fun relaySignedEvent(set: GroupRelaySet) = GroupSignedEvent(
        id = "a" + set.kind.toString().padStart(5, '0') + "0".repeat(58),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = set.kind,
        tags = emptyArray(),
        content = "",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    private fun state(
        groupNostrProfile: GroupNostrProfile?,
        relayLists: List<GroupRelayList> = GroupRelayList.allAmong(emptyList(), chatRoomId),
        canEditNostrProfile: Boolean = true,
        mlsGroupState: String? = "state"
    ) = ChatRoomDetailUIState.Loaded(
        localChatRoom = LocalChatRoom(
            chatRoom = ChatRoom(
                id = chatRoomId,
                userPublicKey = "a".repeat(64),
                // Deliberately not the profile's name. The room's subject is this
                // device's name for it and the profile is the group's own, and a
                // test that used one string for both could not tell them apart.
                subject = "Translation room",
                description = "A group translating hard books.",
                initialGiftWrapPayloadId = "sdfaer",
                mlsGroupState = mlsGroupState
            )
        ),
        // A key state, so the signing key row the section has to sit under is on
        // the screen to be measured against. Not a real key: nothing here derives.
        groupKeyState = GroupKeyState(
            chatRoomId = chatRoomId,
            dkgSessionId = "c".repeat(64),
            thresholdPublicKey = "02".padEnd(66, 'a'),
            derivationPath = "m/9420/0/0",
            announcedBy = chatRoomId,
            announcedAt = Instant.fromEpochSeconds(1_700_000_000)
        ),
        groupNostrProfile = groupNostrProfile,
        groupRelayLists = relayLists,
        canEditNostrProfile = canEditNostrProfile
    )

    /**
     * The screen at a phone's width and a window tall enough to compose the whole
     * of it, since a `LazyColumn` does not lay out what it would not show.
     *
     * The state is passed in rather than loaded, so the NO_OP repositories are
     * never asked for anything: `initiateChatRoomDetail` only runs for a screen
     * that arrives in [ChatRoomDetailUIState.Loading].
     */
    private fun render(
        uiState: ChatRoomDetailUIState,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            CuratedTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = "a".repeat(64),
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = uiState,
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            mantraRepository = MantraRepository.NO_OP_MANTRA_REPOSITORY,
                            onNavigateToRoute = {},
                            onPopBackToRoute = {}
                        )
                    }
                }
            }
        }

        assertions()
    }
}
