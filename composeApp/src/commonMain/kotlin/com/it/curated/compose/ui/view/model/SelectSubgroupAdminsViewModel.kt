package com.it.curated.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.data.ActiveWallet
import fr.acinq.phoenix.managers.nostrPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.it.curated.compose.database.model.types.ChatRoomType
import com.it.curated.compose.managers.MarmotGroupCreation
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.DkgRepository
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.view.state.SelectSubgroupAdminsUIState

/**
 * Who runs a subgroup, what it is called, and on what quorum.
 *
 * All three are settled here and nowhere else. The name and the admin set are
 * carried into the birth certificate, so they are what the parent's admins will
 * be asked to approve; the threshold cannot move afterwards at all, because
 * ChillDKG hashes it and the host keys into the session identity, so `t` is fixed
 * the moment the proposal goes out and a group that disagrees about it gets no
 * key rather than a weak one.
 *
 * It is also the one value picked *for* other people, and consent survives that:
 * `t` rides on the proposal, every invitee's `acceptProposal` re-checks it against
 * `quorumRange` and drops a proposal outside it, and the host-key approval gate is
 * where each of them agrees to the `t`-of-`n` they can now see.
 *
 * Confirming opens the ceremony -- a ritual proposal in the **parent's** room,
 * p-tagged to the picked admins -- and hands back to the parent's transcript,
 * where every admin answers the ceremony from the same place. See
 * `docs/subgroups.md`.
 */
class SelectSubgroupAdminsViewModel(
    val activeUserPublicKey: HexKey,
    val parentChatRoomId: String,
    initialUIState: SelectSubgroupAdminsUIState,
    val activeWalletStateFlow: StateFlow<ActiveWallet?>,
    val chatRepository: ChatRepository,
    val dkgRepository: DkgRepository,
): ViewModel() {

    var uiState: SelectSubgroupAdminsUIState by mutableStateOf(initialUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Members ticked, in the order they were ticked. The coordinator is never here. */
    val selectedPublicKeys = mutableStateListOf<HexKey>()

    /**
     * What the coordinator typed, bare.
     *
     * The `#` is drawn beside the field rather than pushed into this state:
     * normalising on every keystroke would move the caret out from under somebody
     * halfway through a word. It is added by `MarmotGroupName.of` where the
     * subgroup's name is actually used -- in the certificate the parent signs and
     * on the room itself -- which is idempotent, so the two agree.
     */
    val name: MutableState<String> = mutableStateOf("")

    val threshold: MutableState<Int> = mutableStateOf(ChatRoomType.MINIMUM_QUORUM)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val parentRoom = chatRepository.getChatRoomByIdentifier(parentChatRoomId)
            if (parentRoom == null) {
                uiState = SelectSubgroupAdminsUIState.Error("Couldn't load this group")
                return@launch
            }

            // Everybody but this device, since the coordinator is a participant by
            // construction and is not theirs to tick or untick.
            val candidates = parentRoom.localParticipants
                .distinctBy { it.participant.participantPublicKey }
                .filterNot { it.participant.participantPublicKey == activeUserPublicKey }

            uiState = SelectSubgroupAdminsUIState.Loaded(
                parentRoom = parentRoom,
                candidates = candidates,
            )

            resolveKeyPackages(candidates.map { it.participant.participantPublicKey })
        }
    }

    /**
     * Asks every candidate's key package up front.
     *
     * The same move `SelectChatRoomMembersViewModel` makes for group creation, and
     * here it is doing more than saving a round trip. A key package is
     * one-time-use -- `NostrDao` marks the bundle consumed as the device processes
     * its own Welcome -- so every group a member joins burns one, and a member who
     * has not published since has none left. `MarmotGroupCreation` would refuse to
     * create the room for that, correctly, but only after a ChillDKG, a parent
     * quorum and a child quorum had all completed, each needing every selected
     * admin present. Finding out here costs nothing and finding out there costs
     * three ceremonies.
     */
    private suspend fun resolveKeyPackages(publicKeys: List<HexKey>) {
        val resolved = MarmotGroupCreation.keyPackagesFor(chatRepository, publicKeys)
        val missing = resolved.filter { it.second == null }.map { it.first }.toSet()

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return
        uiState = loaded.copy(withoutKeyPackage = missing, isResolvingKeyPackages = false)

        // A member who cannot be welcomed cannot be an admin of a room they are not
        // in, so unpick them rather than leaving a selection that confirm will
        // refuse.
        selectedPublicKeys.removeAll(missing)
    }

    fun toggleMember(publicKey: HexKey) {
        if (isActionPending.value) return

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return
        if (publicKey in loaded.withoutKeyPackage) return

        if (!selectedPublicKeys.remove(publicKey)) {
            selectedPublicKeys.add(publicKey)
        }

        // The range moves with the admin count, so a threshold picked at one size
        // has to be pulled back into the range at another.
        threshold.value = threshold.value.coerceIn(quorumRange())
    }

    fun isSelected(publicKey: HexKey): Boolean = selectedPublicKeys.contains(publicKey)

    fun setName(value: String) {
        if (isActionPending.value) return

        name.value = value
    }

    /** The subgroup's admins: everyone ticked, plus the coordinator. */
    fun adminCount(): Int = selectedPublicKeys.size + 1

    fun quorumRange(): IntRange =
        ChatRoomType.quorumRange(maxOf(adminCount(), ChatRoomType.MINIMUM_QUORUM))

    fun setThreshold(value: Int) {
        if (isActionPending.value) return

        threshold.value = value.coerceIn(quorumRange())
    }

    /**
     * Why this cannot be confirmed yet, or null when it can.
     *
     * Every message names what to do rather than what is wrong, because each of
     * these is something the user can fix from where they are standing.
     */
    suspend fun refusal(): String? {
        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return "Still loading"

        if (name.value.isBlank()) return "Give the subgroup a name."

        if (loaded.isResolvingKeyPackages) return "Checking who can be added…"

        val unreachable = selectedPublicKeys.filter { it in loaded.withoutKeyPackage }
        if (unreachable.isNotEmpty()) {
            return "Some of the people picked have no key package. Ask them to " +
                "publish one, then try again."
        }

        return chatRepository.refuseSubgroup(
            parentChatRoomId = parentChatRoomId,
            adminPublicKeys = selectedPublicKeys.toSet(),
            coordinatorPublicKey = activeUserPublicKey
        )
    }

    /**
     * Opens the ceremony in the parent's room and hands back to its transcript.
     *
     * No sibling room is made. The ceremony runs where the parent's members
     * already are: the proposal names the picked admins in its p-tags, which is
     * the participant set every device derives `n` from, and a parent member who
     * is not in it drops the proposal rather than joining. It carries the name
     * and the parent for the same reason it always did -- the child has no room
     * of its own to read either off until the ceremony has produced its key.
     */
    fun confirm(onNavigateToRoute: (Route) -> Unit) {
        if (isActionPending.value) return

        val loaded = uiState as? SelectSubgroupAdminsUIState.Loaded ?: return

        val nostrPrivateKey = activeWalletStateFlow.value
            ?.business?.walletManager?.keyManager?.value?.nostrPrivateKey()
        if (nostrPrivateKey == null) {
            uiState = SelectSubgroupAdminsUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // Checked again here rather than trusted from the button: a screen not
            // drawing something is not a guard, and the key-package answer can go
            // stale while the screen is open.
            val refusal = refusal()
            if (refusal != null) {
                isActionPending.value = false
                uiState = SelectSubgroupAdminsUIState.Error(refusal)
                return@launch
            }

            // The bare name goes on the ceremony, and the `#` is added where the
            // name is used: `proposeBirthCertificate` normalises the name the
            // parent signs and `MarmotGroupCreation` normalises the name the room
            // carries, both reading it back off this session.
            // `MarmotGroupName.of` is idempotent, so those are the same string --
            // which they have to be, or the room is not called what its parent
            // certified.
            //
            // Carried on the session rather than on a room, because there is no
            // longer a room to carry it: the ceremony is held in the parent's,
            // and the parent's name is the one name a child must not take.
            val session = dkgRepository.proposeRitual(
                localChatRoom = loaded.parentRoom,
                userPublicKey = activeUserPublicKey,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray(),
                threshold = threshold.value,
                parentChatRoomId = parentChatRoomId,
                // The admins, and only them. The room is the parent's, so the
                // ceremony has to say who it is with -- this is the set that
                // becomes the proposal's p-tags and every device's `n`. The
                // coordinator is added by `proposeRitual`: opening a ceremony is
                // being in it, and is not theirs to tick.
                participantPublicKeys = selectedPublicKeys.toSet(),
                subject = name.value.trim()
            )

            isActionPending.value = false

            if (session == null) {
                logger.e("Could not open a subgroup ceremony in $parentChatRoomId")
                uiState = SelectSubgroupAdminsUIState.Error(
                    "Couldn't start the subgroup's key ceremony. Please try again."
                )
                return@launch
            }

            withContext(Dispatchers.Main) {
                // The parent's transcript, not the shared-key screen. The ceremony
                // is already open and its first line is already there, so what the
                // coordinator needs is to watch it and answer the requests as they
                // arrive -- which is now literally the same place every other
                // admin answers from, through the ritual notices that name their
                // own ceremony.
                //
                // Sending them to the shared-key screen instead was actively
                // harmful: that screen's job is to *offer* a ceremony to a room
                // that has none, so the coordinator arrived at a "Start key
                // ceremony" button seconds after starting one, and pressing it
                // opened a second -- for no subgroup, so `proposeRitual`'s guard
                // did not recognise it as a duplicate -- which then buried the
                // subgroup's under the room's newest.
                onNavigateToRoute(
                    ChatRoomMessagingRoute(
                        activeUserPublicKey = activeUserPublicKey,
                        chatRoomId = parentChatRoomId,
                        relayHint = null
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "SelectSubgroupAdminsViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            parentChatRoomId: String,
            initialUIState: SelectSubgroupAdminsUIState = SelectSubgroupAdminsUIState.Loading,
            activeWalletStateFlow: StateFlow<ActiveWallet?>,
            chatRepository: ChatRepository,
            dkgRepository: DkgRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SelectSubgroupAdminsViewModel(
                    activeUserPublicKey = activeUserPublicKey,
                    parentChatRoomId = parentChatRoomId,
                    initialUIState = initialUIState,
                    activeWalletStateFlow = activeWalletStateFlow,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository
                )
            }
        }
    }
}
