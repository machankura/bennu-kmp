package com.machankura.bennu.compose.ui.view.model

import com.machankura.bennu.compose.database.model.GroupSignedEvent
import com.machankura.bennu.compose.nostr.GroupRelay
import com.machankura.bennu.compose.nostr.GroupRelayList
import com.machankura.bennu.compose.nostr.GroupRelaySet
import com.machankura.bennu.compose.nostr.Relays
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.repository.FrostSigningRepository
import com.machankura.bennu.compose.ui.view.state.EditGroupRelaysUIState
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The two decisions the relay editor makes that nothing else can check.
 *
 * **What counts as a change.** The button proposes only the lists that differ, so
 * this is what decides whether a quorum is asked at all -- and getting it wrong in
 * either direction is quiet. Too eager and every visit re-signs four lists nobody
 * touched, dating a decision the group did not make; too shy and an edit is
 * silently dropped on a screen that said it had been sent.
 *
 * **What a NIP-65 marker is allowed to become.** A relay that is neither read nor
 * write has no tag in the format, so the state must not be reachable.
 * `GroupRelaySet.template` drops one as a last resort; this is where it is actually
 * prevented.
 */
class EditGroupRelaysViewModelJvmTest {
    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")

    private fun viewModel() = EditGroupRelaysViewModel(
        chatRoomId = chatRoomId,
        activeUserPublicKey = "a".repeat(64),
        relayHint = null,
        initialEditGroupRelaysUIState = EditGroupRelaysUIState.Loading,
        chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
        frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
    )

    private fun signedEvent(set: GroupRelaySet) = GroupSignedEvent(
        id = "a" + set.kind.toString().padStart(5, '0') + "0".repeat(58),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = set.kind,
        tags = emptyArray(),
        content = "",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    /** Every set unsigned, which is a group opening the editor for the first time. */
    private fun nothingSigned() = GroupRelayList.allAmong(emptyList(), chatRoomId)

    private fun signed(set: GroupRelaySet, relays: List<GroupRelay>) =
        nothingSigned().map {
            if (it.set == set) GroupRelayList(set, signedEvent(set), relays) else it
        }

    @Test
    fun `a first-time group is seeded with the app's own relay and that is a change`() {
        val signed = nothingSigned()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)

        assertEquals(
            listOf(Relays.ephemeral),
            viewModel.relaysOf(GroupRelaySet.General).map { it.url },
        )

        // Seeded and therefore proposable. A group whose editor filled itself in
        // and then refused to send it would be showing a plan it will not carry out.
        assertEquals(
            listOf(GroupRelaySet.General, GroupRelaySet.Messages, GroupRelaySet.Search),
            viewModel.changedSets(signed),
        )
    }

    @Test
    fun `blocked is never seeded and so is never proposed by simply opening the screen`() {
        val signed = nothingSigned()
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)

        assertEquals(emptyList(), viewModel.relaysOf(GroupRelaySet.Blocked))
        assertFalse(GroupRelaySet.Blocked in viewModel.changedSets(signed))
    }

    @Test
    fun `opening a group that has agreed everything proposes nothing`() {
        // The case that has to be silent. A member opening the editor, reading it
        // and pressing the button must not spend a quorum on four identical lists.
        val signed = GroupRelaySet.entries.map { set ->
            GroupRelayList(set, signedEvent(set), listOf(GroupRelay(one)))
        }

        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)

        assertEquals(emptyList(), viewModel.changedSets(signed))
    }

    @Test
    fun `only the set that was edited is proposed`() {
        val signed = GroupRelaySet.entries.map { set ->
            GroupRelayList(set, signedEvent(set), listOf(GroupRelay(one)))
        }

        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)
        viewModel.selectSet(GroupRelaySet.Search)
        assertTrue(viewModel.addRelay(two.url))

        assertEquals(listOf(GroupRelaySet.Search), viewModel.changedSets(signed))
    }

    @Test
    fun `reordering a list is a change`() {
        // A relay list is written in the order it is read back, and a member who
        // moved a relay to the front meant to. Comparing as sets would call this no
        // change and refuse to propose it.
        val signed = signed(GroupRelaySet.General, listOf(GroupRelay(one), GroupRelay(two)))

        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)
        viewModel.selectSet(GroupRelaySet.General)
        viewModel.removeRelay(GroupRelaySet.General, GroupRelay(one))
        assertTrue(viewModel.addRelay(one.url))

        assertEquals(
            listOf(two, one),
            viewModel.relaysOf(GroupRelaySet.General).map { it.url },
        )
        assertTrue(GroupRelaySet.General in viewModel.changedSets(signed))
    }

    @Test
    fun `emptying an agreed list is a change, and emptying an unagreed one is not`() {
        // Withdrawing a list the group agreed is a decision worth a signature.
        // "Still nothing" is not a decision at all, and proposing an empty list for
        // a set nobody ever set would be a signature over silence.
        val agreed = signed(GroupRelaySet.Messages, listOf(GroupRelay(one)))
        val withdrawing = viewModel()
        withdrawing.seedWorkingCopy(agreed)
        withdrawing.removeRelay(GroupRelaySet.Messages, GroupRelay(one))

        assertTrue(GroupRelaySet.Messages in withdrawing.changedSets(agreed))

        val untouched = nothingSigned()
        val quiet = viewModel()
        quiet.seedWorkingCopy(untouched)
        // Clear the seeded default so the set is unagreed *and* empty.
        quiet.removeRelay(GroupRelaySet.Search, GroupRelay(Relays.ephemeral))

        assertFalse(GroupRelaySet.Search in quiet.changedSets(untouched))
    }

    @Test
    fun `a relay cannot be turned into one that is neither read nor write`() {
        // NIP-65 has no tag for it, so the state has to be unreachable. What it
        // would mean is that the relay is not in the list, and removing it is the
        // row's own button.
        val signed = signed(GroupRelaySet.General, listOf(GroupRelay(one)))
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)

        viewModel.toggleWrite(GroupRelaySet.General, GroupRelay(one))
        assertEquals(
            listOf(GroupRelay(one, read = true, write = false)),
            viewModel.relaysOf(GroupRelaySet.General),
        )

        // The second toggle is the one that would leave it as neither.
        viewModel.toggleRead(
            GroupRelaySet.General,
            viewModel.relaysOf(GroupRelaySet.General).first(),
        )
        assertEquals(
            listOf(GroupRelay(one, read = true, write = false)),
            viewModel.relaysOf(GroupRelaySet.General),
            "a relay was left neither read nor write, which NIP-65 cannot express",
        )
    }

    @Test
    fun `a url that is not a relay is refused and named`() {
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(nothingSigned())
        viewModel.selectSet(GroupRelaySet.Search)

        assertFalse(viewModel.addRelay("http://relay.one.example"))
        assertEquals(EditGroupRelaysViewModel.AddFailure.NotARelay, viewModel.addFailure)

        // And a duplicate is a no-op rather than a mistake, said differently.
        assertTrue(viewModel.addRelay(one.url))
        assertFalse(viewModel.addRelay(one.url))
        assertEquals(EditGroupRelaysViewModel.AddFailure.AlreadyListed, viewModel.addFailure)
    }

    @Test
    fun `seeding again leaves an edit alone`() {
        // The effect that seeds is keyed on a state the view model can re-emit, so
        // running twice has to be harmless -- otherwise a reload halfway through
        // typing throws the typing away.
        val signed = signed(GroupRelaySet.General, listOf(GroupRelay(one)))
        val viewModel = viewModel()
        viewModel.seedWorkingCopy(signed)
        viewModel.selectSet(GroupRelaySet.General)
        assertTrue(viewModel.addRelay(two.url))

        viewModel.seedWorkingCopy(signed)

        assertEquals(
            listOf(one, two),
            viewModel.relaysOf(GroupRelaySet.General).map { it.url },
        )
    }
}
