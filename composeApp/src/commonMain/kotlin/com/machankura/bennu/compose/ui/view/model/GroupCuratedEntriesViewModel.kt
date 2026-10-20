package com.machankura.bennu.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.machankura.bennu.compose.network.relays.EventPublishTransport
import com.machankura.bennu.compose.nostr.GroupCuratedEntry
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.ui.view.model.BroadcastGroupSignedEventViewModel.RelayOutcome
import com.machankura.bennu.compose.ui.view.state.GroupCuratedEntriesUIState

/**
 * The entries the group has accepted into its lists, read once for the screen
 * that shows them. One read rather than a watch, for the reason
 * `GroupNostrProfileViewModel` gives. Nothing here asks whether this device
 * can sign, because nothing on the screen would change if it could.
 *
 * ### Broadcast all
 *
 * The one thing the screen does besides show: [broadcastAll] sends every
 * entry, one after another, each to the relays its own broadcast screen would
 * have filled in -- `defaultRelaysFor`, read against the group's relay lists
 * and the entry's own schema -- and says under each card what its relays
 * answered. The member sees nothing before pressing, which is the difference
 * from sending one at a time: that screen exists so the relays can be read
 * and changed first, and this button is for the member who has read them
 * once and has twenty entries to send. The seeded list is what a member who
 * reads nothing and presses the button sends to there too.
 *
 * One entry at a time rather than all at once, for two reasons. The rows fill
 * in top to bottom, so the member can see where the batch is; and a public
 * relay sent twenty events in one burst answers "rate-limited" to most of
 * them, which would make the button worse than the twenty presses it saves.
 * Each entry's send is bounded by the same ceiling as a single broadcast,
 * and the whole batch by nothing but that, since a long list is meant to
 * take a while. It goes to the pool directly rather than through the queue,
 * for the reason `BroadcastGroupSignedEventViewModel` gives, and it is only
 * as durable as the button press: leaving the screen ends it where it is.
 */
class GroupCuratedEntriesViewModel(
    val chatRoomId: String,
    initialGroupCuratedEntriesUIState: GroupCuratedEntriesUIState,
    val chatRepository: ChatRepository,
    val publishTransport: EventPublishTransport,
): ViewModel() {

    var groupCuratedEntriesUIState: GroupCuratedEntriesUIState by mutableStateOf(
        initialGroupCuratedEntriesUIState
    )
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /**
     * How each entry's send went the last time the button was pressed, keyed
     * by the signed event's id. An entry the batch has not reached yet has
     * none, which is how the screen tells "not yet" from "sending".
     */
    val entryBroadcasts = mutableStateMapOf<HexKey, EntryBroadcast>()

    /**
     * Where the last press has got to, or null before the first one.
     *
     * On the screen rather than in a snackbar, for the reason the one-event
     * screen gives: it is the answer the member came for, and it should still
     * be there once the cards under it have been read. Cleared when the next
     * press starts.
     */
    var broadcastAllProgress: BroadcastAllProgress? by mutableStateOf(null)
        private set

    /** One entry's part of one press of the button. */
    sealed interface EntryBroadcast {
        /** Sent, and not every relay has answered yet. */
        data object Sending : EntryBroadcast

        /**
         * Not sent, because there was nowhere to send it: everything the seed
         * rule would have named is on the group's blocked list. The one-event
         * screen meets the same case as an empty list and a snackbar.
         */
        data object NoRelays : EntryBroadcast

        /** Every relay has answered, or been given up on. */
        data class Sent(val outcomes: Map<NormalizedRelayUrl, RelayOutcome>) : EntryBroadcast {
            val accepted: Int get() = outcomes.count { it.value == RelayOutcome.Accepted }

            val asked: Int get() = outcomes.size

            val isAcceptedEverywhere: Boolean get() = accepted == asked

            /**
             * The relays that did not say OK, with what they said instead, in
             * the order they were asked. Named under the count because the
             * relay's reason is what a member can act on, and a count is not.
             */
            val refusals: List<Pair<NormalizedRelayUrl, RelayOutcome>>
                get() = outcomes.filterValues { it != RelayOutcome.Accepted }.toList()
        }
    }

    /** How far the last press has got, and then how it went. */
    sealed interface BroadcastAllProgress {
        /** Entry [entry] of [total] is out, counting from one. */
        data class Sending(val entry: Int, val total: Int) : BroadcastAllProgress

        /**
         * Every entry has been sent, and [acceptedEverywhere] of [total] were
         * accepted by every relay they went to. The rest say under their own
         * cards which relay refused, and why.
         */
        data class Done(val acceptedEverywhere: Int, val total: Int) : BroadcastAllProgress
    }

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupCuratedEntriesUIState = if (localChatRoom == null) {
                GroupCuratedEntriesUIState.Error("Couldn't find the group")
            } else {
                GroupCuratedEntriesUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupCuratedEntries = chatRepository.groupCuratedEntries(chatRoomId),
                    relayLists = chatRepository.groupRelayLists(chatRoomId),
                )
            }
        }
    }

    /**
     * Sends every entry on the screen, in the order it shows them, and records
     * what each one's relays said.
     *
     * Returns the batch, for a caller that has to wait for it, and null when
     * nothing was sent: a press while one is already out, or a screen with no
     * entries on it, which has no button.
     */
    fun broadcastAll(): Job? {
        // Guard against double submits. A second press while the first is out
        // would send every entry twice and count each answer twice.
        if (isActionPending.value) return null

        val state = groupCuratedEntriesUIState as? GroupCuratedEntriesUIState.Loaded ?: return null
        val entries = state.groupCuratedEntries
        if (entries.isEmpty()) return null

        isActionPending.value = true
        broadcastAllProgress = null
        entryBroadcasts.clear()

        return viewModelScope.launch(Dispatchers.IO) {
            var acceptedEverywhere = 0

            entries.forEachIndexed { index, accepted ->
                broadcastAllProgress = BroadcastAllProgress.Sending(entry = index + 1, total = entries.size)

                val sent = send(accepted, state)
                entryBroadcasts[accepted.signedEvent.id] = sent
                if (sent is EntryBroadcast.Sent && sent.isAcceptedEverywhere) acceptedEverywhere++
            }

            broadcastAllProgress = BroadcastAllProgress.Done(
                acceptedEverywhere = acceptedEverywhere,
                total = entries.size,
            )

            isActionPending.value = false
        }
    }

    /**
     * One entry, to where its own broadcast screen would have sent it.
     *
     * The relays are the seed rule's, read against the group's lists and the
     * entry's own schema -- an entry carries the list it is in, so the rule
     * is handed that one schema rather than all of the group's.
     */
    private suspend fun send(
        accepted: GroupCuratedEntry,
        state: GroupCuratedEntriesUIState.Loaded,
    ): EntryBroadcast {
        val signedEvent = accepted.signedEvent
        val targets = BroadcastGroupSignedEventViewModel.defaultRelaysFor(
            relayLists = state.relayLists,
            kind = signedEvent.kind,
            tags = signedEvent.tags,
            content = signedEvent.content,
            schemas = listOf(accepted.list),
        )

        if (targets.isEmpty()) return EntryBroadcast.NoRelays

        entryBroadcasts[signedEvent.id] = EntryBroadcast.Sending

        return EntryBroadcast.Sent(
            BroadcastGroupSignedEventViewModel.sendToRelays(publishTransport, signedEvent, targets)
        )
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialGroupCuratedEntriesUIState: GroupCuratedEntriesUIState =
                GroupCuratedEntriesUIState.Loading,
            chatRepository: ChatRepository,
            publishTransport: EventPublishTransport,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupCuratedEntriesViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupCuratedEntriesUIState = initialGroupCuratedEntriesUIState,
                    chatRepository = chatRepository,
                    publishTransport = publishTransport,
                )
            }
        }
    }
}
