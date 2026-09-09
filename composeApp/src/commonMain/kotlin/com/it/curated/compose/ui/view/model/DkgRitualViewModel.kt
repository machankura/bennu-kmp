package com.it.curated.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.it.curated.compose.database.model.types.DkgApprovalStep
import com.it.curated.compose.database.model.types.DkgRitualStage
import com.it.curated.compose.database.model.types.FrostSigningStage
import com.it.curated.compose.database.model.types.ChatRoomType
import com.it.curated.compose.managers.ChillDkgRitualManager
import com.it.curated.compose.managers.MarmotGroupCreation
import com.it.curated.compose.managers.SharedKeyDerivation
import com.it.curated.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers.Main
import com.it.curated.compose.nostr.dkg.DkgRitualEvents
import com.it.curated.compose.nostr.frost.GroupKeyStateEvent
import com.it.curated.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.it.curated.compose.repository.ChatRepository
import com.it.curated.compose.repository.DkgRepository
import com.it.curated.compose.ui.view.state.DkgRitualUIState
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.phoenix.data.ActiveWallet
import fr.acinq.phoenix.managers.nostrPrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Watches a group's ChillDKG ritual, and opens one on request.
 *
 * Nothing here drives the protocol — the ritual advances in
 * [ChillDkgRitualManager] as messages arrive on the inbound path, so this only
 * has to read the session and show it moving.
 */
class DkgRitualViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    /**
     * The ceremony to show, when the caller knew which. Null falls back to the
     * room's newest -- see [DkgRitualRoute.dkgSessionId].
     */
    val dkgSessionId: String? = null,
    initialDkgRitualUIState: DkgRitualUIState,
    val activeWalletStateFlow: StateFlow<ActiveWallet?>,
    val chatRepository: ChatRepository,
    val dkgRepository: DkgRepository,
): ViewModel() {

    var dkgRitualUIState: DkgRitualUIState by mutableStateOf(initialDkgRitualUIState)
        private set

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Watches the current session's messages; replaced whenever the session changes. */
    private var messageObserver: Job? = null

    /** Watches the room's signing sessions, which is where the key state is agreed. */
    private var keyStateObserver: Job? = null

    /** Watches the parent certifying this subgroup; re-pointed as the purpose is learned. */
    private var certificateObserver: Job? = null

    /**
     * The parent [observeCertificate] is currently watching, so a re-emission of
     * the same session does not tear the watchers down and stand them up again.
     */
    private var watchedParentChatRoomId: HexKey? = null

    /** The ceremony [observeForCeremony] is currently watching. */
    private var watchedSessionId: String? = null

    /**
     * The quorum the ritual will generate a key for. Pre-filled with the same
     * majority default the group-creation screen offers.
     *
     * TODO: the quorum chosen at creation time is not persisted on ChatRoom, so
     *  it cannot be read back here. Storing it there would let this default to
     *  what the group was actually promised rather than re-deriving it.
     */
    val threshold: MutableState<Int> = mutableStateOf(ChatRoomType.MINIMUM_QUORUM)

    /**
     * Whether this device can open a ceremony right now.
     *
     * Any member may: ChillDKG gives the coordinator no say in the outcome, and a
     * NIP-17 room records no creator to reserve the job for. What rules it out is
     * a ceremony already running, or a group too small to split a key across.
     */
    fun canStartRitual(): Boolean {
        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return false

        if (loaded.session != null && loaded.session.stage != DkgRitualStage.FAILED) return false

        // Not just the ceremony on screen. A room can hold more than one, and this
        // screen exists to offer one to a room that has none -- so a room with a
        // ceremony running under a different purpose must not be offered another,
        // whatever this screen happens to be showing.
        if (loaded.roomHasCeremony) return false

        return ChillDkgRitualManager.canRunRitual(loaded.localChatRoom)
    }

    fun quorumRange(): IntRange {
        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded
            ?: return ChatRoomType.quorumRange(ChatRoomType.MINIMUM_QUORUM)

        // Below the minimum the range runs backwards and is empty, which `coerceIn`
        // rejects outright. There is no ritual to run at that size either.
        return ChatRoomType.quorumRange(
            maxOf(loaded.participantCount, ChatRoomType.MINIMUM_QUORUM)
        )
    }

    fun setThreshold(value: Int) {
        if (isActionPending.value) return

        threshold.value = value.coerceIn(quorumRange())
    }

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (localChatRoom == null) {
                dkgRitualUIState = DkgRitualUIState.Error("Couldn't load this chat")
                return@launch
            }

            dkgRitualUIState = DkgRitualUIState.Loaded(localChatRoom = localChatRoom)
            threshold.value = ChatRoomType
                .defaultQuorum(ChillDkgRitualManager.memberPublicKeys(localChatRoom).size)
                .coerceIn(quorumRange())

            observeKeyState()

            // Room-scoped, deliberately. Only the member who opened a subgroup
            // arrives here knowing it is one -- everybody else reaches this screen
            // from the room, through the transcript's ritual notice or the group's
            // details, neither of which has a purpose to hand it. Filtering by one
            // here left every other member looking at a room whose ceremony had
            // started and being told none had.
            //
            // So the purpose is read off the session rather than required to open
            // it. `getLatestSessionFor` is still scoped where scoping is the
            // question being asked -- "may I open another" -- in `proposeRitual`
            // and `refuseCeremonyRoom`.
            // The named ceremony where the caller knew which, and the room's
            // newest otherwise. A room holds more than one the moment a subgroup's
            // admins are the whole group, and then "the room's newest" is whichever
            // ceremony was opened last -- routinely the group's own, already
            // finished, while the subgroup's request goes unanswered underneath it.
            val sessions = dkgSessionId
                ?.let { dkgRepository.observeSessionById(it) }
                ?: dkgRepository.observeLatestSessionForChatRoom(chatRoomId)

            // Whether the room has any ceremony at all, which is a different
            // question from which one is being shown -- see
            // `DkgRitualUIState.roomHasCeremony`.
            launch {
                dkgRepository.observeLatestSessionForChatRoom(chatRoomId).collect { newest ->
                    val current = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return@collect

                    dkgRitualUIState = current.copy(
                        roomHasCeremony = newest != null && newest.stage != DkgRitualStage.FAILED
                    )
                }
            }

            sessions.collect { session ->
                val loaded = (dkgRitualUIState as? DkgRitualUIState.Loaded)
                    ?: DkgRitualUIState.Loaded(localChatRoom = localChatRoom)

                // The session is the only thing that knows what the ceremony is
                // for. The route used to carry it too, which was right for the one
                // member who opened the subgroup and null for everybody else.
                val parent = session?.parentChatRoomId

                dkgRitualUIState = loaded.copy(
                    session = session,
                    parentChatRoomId = parent,
                    pendingApproval = session?.let { dkgRepository.pendingApproval(it) }
                )

                // Re-point the per-ceremony watchers whenever the ceremony or its
                // purpose changes, which for a member arriving cold is once.
                // Everything downstream of this -- the key state, the certificate
                // -- is about one ceremony, and the room cannot say which.
                if (session?.id != watchedSessionId || parent != watchedParentChatRoomId) {
                    watchedSessionId = session?.id
                    watchedParentChatRoomId = parent
                    observeForCeremony(session?.id, parent)
                }

                // Re-point the message watcher at whatever session is current. An
                // abandoned ritual's counts must not keep ticking over the new one.
                messageObserver?.cancel()
                if (session != null) {
                    messageObserver = viewModelScope.launch(Dispatchers.IO) {
                        dkgRepository.observeMessages(session.id).collect { messages ->
                            val current = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return@collect

                            fun senders(kind: Kind) = messages
                                .filter { it.kind == kind }
                                .map { it.participantPublicKey }
                                .toSet()

                            dkgRitualUIState = current.copy(
                                hostKeyParticipants = senders(DkgRitualEvents.HOST_KEY),
                                round1Participants = senders(DkgRitualEvents.ROUND_1),
                                round2Participants = senders(DkgRitualEvents.ROUND_2),
                                // Recomputed here as well as on the session, because
                                // whether round 1 can be asked for depends on how many
                                // host keys have arrived, which only this flow sees.
                                pendingApproval = current.session
                                    ?.let { dkgRepository.pendingApproval(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Follows the group agreeing what its admin room will sign with.
     *
     * Nothing here watches a [GroupKeyState] row, because there is no room yet
     * for one to belong to -- being able to follow this before the room exists
     * is the point. What is watched instead is the session, for how the signing
     * is going, and the signed event, for whether it finished.
     *
     * The room a ceremony runs in signs nothing else, so the newest session in
     * it is the key state's -- but it is matched on the kind it carries anyway,
     * so a room that one day signs something else does not confuse the two.
     */
    private fun observeKeyState() {
        keyStateObserver?.cancel()
        keyStateObserver = viewModelScope.launch(Dispatchers.IO) {
            // Two flows because they answer two questions off two tables. How
            // the signing is going is the session row; whether it finished is
            // the signed event, and only that second one can be asked at all
            // before there is a room -- which is the whole point of asking it.
            launch {
                dkgRepository.observeSigningSessions(chatRoomId).collect { sessions ->
                    val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return@collect

                    dkgRitualUIState = loaded.copy(
                        keyStateSession = sessions.firstOrNull { local ->
                            local.items.any { item ->
                                runCatching {
                                    Event.fromJson(item.unsignedEventJson).kind
                                }.getOrNull() == GroupKeyStateEvent.KIND
                            }
                        }?.session
                    )
                }
            }


        }
    }

    /**
     * Follows one ceremony: whether its key state has been signed, and -- for a
     * subgroup -- whether its parent has certified it.
     *
     * Off the *parent's* signed events and sessions rather than this room's: the
     * certificate is made where the parent's key can sign it, which is never the
     * ceremony's room.
     *
     * Started from the session collector rather than at init, because a member
     * who did not open the subgroup has no idea it is one until the proposal they
     * were sent turns up. Cancelled and restarted on a change, the way the
     * message watcher is -- an ordinary ceremony passes null and gets nothing
     * watched, which is exactly right.
     */
    private fun observeForCeremony(dkgSessionId: String?, parentChatRoomId: HexKey?) {
        certificateObserver?.cancel()

        if (dkgSessionId == null) {
            dkgRitualUIState = (dkgRitualUIState as? DkgRitualUIState.Loaded)
                ?.copy(isKeyStateSigned = false, isCertified = false, certificateSession = null)
                ?: dkgRitualUIState
            return
        }

        certificateObserver = viewModelScope.launch(Dispatchers.IO) {
            // Whether the group has agreed what the room this ceremony's key
            // derives will sign with. Asked by ceremony rather than by room: the
            // room holds however many ceremonies it holds, and the answer is
            // about this one's key.
            launch {
                dkgRepository.observeSignedGroupKeyState(dkgSessionId).collect { state ->
                    val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return@collect

                    dkgRitualUIState = loaded.copy(isKeyStateSigned = state != null)
                }
            }

            if (parentChatRoomId == null) {
                dkgRitualUIState = (dkgRitualUIState as? DkgRitualUIState.Loaded)
                    ?.copy(isCertified = false, certificateSession = null)
                    ?: dkgRitualUIState
                return@launch
            }

            launch {
                dkgRepository.observeBirthCertificate(dkgSessionId, parentChatRoomId)
                    .collect { certificate ->
                        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return@collect

                        dkgRitualUIState = loaded.copy(isCertified = certificate != null)
                    }
            }

            launch {
                dkgRepository.observeSigningSessions(parentChatRoomId).collect { sessions ->
                    val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return@collect

                    dkgRitualUIState = loaded.copy(
                        certificateSession = sessions.firstOrNull { local ->
                            local.items.any { item ->
                                runCatching {
                                    Event.fromJson(item.unsignedEventJson).kind
                                }.getOrNull() == SubgroupBirthCertificateEvent.KIND
                            }
                        }?.session
                    )
                }
            }
        }
    }

    /**
     * Asks the group to sign what its admin room will sign with.
     *
     * The step before the room exists, and the reason it can be created knowing
     * its own key state rather than being told afterwards. Offered to the
     * ceremony's coordinator, who is also the member who will create the room.
     *
     * Nothing here waits for the answer: the signing runs on arriving messages
     * like everything else, and the screen follows it through [observeKeyState].
     */
    fun proposeKeyState() {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        val session = loaded.session ?: return
        if (session.thresholdPublicKey == null) return

        // Nothing to ask for, and nobody to ask twice. A failed session is the
        // one case a second proposal is right, and it has to be a new session
        // rather than that one resumed -- see `FrostSigningManager`, which is
        // why `propose` mints a fresh id every time.
        if (loaded.isKeyStateSigned) return
        loaded.keyStateSession?.let { if (it.stage != FrostSigningStage.FAILED) return }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            // A subgroup's state carries the parent's certificate, and refuses
            // to be proposed without one -- every device that receives it runs
            // the same check and drops it, so proposing one this device would not
            // believe spends a quorum's attention on nothing.
            val subgroupParent = loaded.parentChatRoomId

            val signing = if (subgroupParent != null) {
                dkgRepository.proposeSubgroupKeyState(
                    ceremonyRoom = loaded.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    session = session,
                    parentChatRoomId = subgroupParent
                )
            } else {
                dkgRepository.proposeGroupKeyState(
                    localChatRoom = loaded.localChatRoom,
                    userPublicKey = activeUserPublicKey,
                    session = session
                )
            }

            isActionPending.value = false

            if (signing == null) {
                logger.e("Failed to propose the key state for $chatRoomId")
                dkgRitualUIState = DkgRitualUIState.Error(
                    "Couldn't ask the group to agree its signing key. Please try again."
                )
            }
            // On success the session flow above delivers the new row; no need to
            // set it here and risk racing the observer.
        }
    }

    /**
     * Asks the parent's quorum to certify the subgroup this ceremony produced.
     *
     * Step 2 of four, and the only one that runs somewhere else: the certificate
     * is signed in the *parent's* room, because it is the parent's signature.
     * Offered to whoever holds a share of the parent's key, which the coordinator
     * does by construction -- they are a parent admin, that is how they got here.
     *
     * Nothing waits for the answer. The session runs on arriving messages like
     * every other, and [observeKeyState] follows it.
     */
    fun proposeBirthCertificate() {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        // The state's, not the route's. Every step after the ceremony is open to
        // any of the subgroup's admins -- see `SubgroupManager` -- and they reach
        // this screen from the room, with no route to have told them anything.
        val parent = loaded.parentChatRoomId ?: return
        val session = loaded.session ?: return
        if (session.thresholdPublicKey == null) return

        // Nothing to ask for, and nobody to ask twice. A failed session is the
        // one case a second proposal is right, and it has to be a new session
        // rather than that one resumed.
        if (loaded.isCertified) return
        loaded.certificateSession?.let { if (it.stage != FrostSigningStage.FAILED) return }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val signing = dkgRepository.proposeBirthCertificate(
                parentChatRoomId = parent,
                userPublicKey = activeUserPublicKey,
                session = session,
                // The ceremony's participants, not the room's members. The
                // ceremony runs in the parent's room, so the room's roster is
                // everybody the subgroup is *not* -- putting it here would ask the
                // parent to certify itself as its own child.
                adminPublicKeys = ChillDkgRitualManager
                    .participantsOf(session, loaded.localChatRoom)
                    .toList(),
                // The name the coordinator typed, carried to every other admin on
                // the proposal -- so this is the same name they have all been
                // looking at, and the one the parent's quorum is about to sign.
                name = session.subject ?: "Subgroup"
            )

            isActionPending.value = false

            if (signing == null) {
                logger.e("Failed to propose a birth certificate in $parent")
                dkgRitualUIState = DkgRitualUIState.Error(
                    "Couldn't ask the group to certify this subgroup. Please try again."
                )
            }
        }
    }

    /** Opens a ritual, making this device its coordinator, and publishes the proposal. */
    fun startRitual() {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return

        val nostrPrivateKey = activeWalletStateFlow.value?.business?.walletManager?.keyManager?.value?.nostrPrivateKey()
        if (nostrPrivateKey == null) {
            dkgRitualUIState = DkgRitualUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val session = dkgRepository.proposeRitual(
                localChatRoom = loaded.localChatRoom,
                userPublicKey = activeUserPublicKey,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray(),
                threshold = threshold.value
            )

            isActionPending.value = false

            if (session == null) {
                logger.e("Failed to start DKG ritual for $chatRoomId")
                dkgRitualUIState = DkgRitualUIState.Error("Couldn't start the key ceremony. Please try again.")
            }
            // On success the session flow above delivers the new row; no need to
            // set it here and risk racing the observer.
        }
    }

    /**
     * Agrees to whatever the ritual is currently waiting on from this device.
     *
     * Takes the step rather than reading it back from the state so a screen opened
     * for one step cannot approve a different one it has since moved on to — the
     * manager checks it against the ritual again and ignores a mismatch.
     */
    fun approve(step: DkgApprovalStep) {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        val session = loaded.session ?: return

        val nostrPrivateKey = activeWalletStateFlow.value?.business?.walletManager?.keyManager?.value?.nostrPrivateKey()
        if (nostrPrivateKey == null) {
            dkgRitualUIState = DkgRitualUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        isActionPending.value = true

        viewModelScope.launch(Dispatchers.IO) {
            dkgRepository.approve(
                localChatRoom = loaded.localChatRoom,
                sessionId = session.id,
                step = step,
                nostrPrivateKey = nostrPrivateKey.value.toByteArray()
            )

            isActionPending.value = false
            // The session flow delivers the updated row and with it the next pending
            // step, so nothing is set here.
        }
    }

    /**
     * Opens the group's admin room: a Marmot/MLS chat whose every member is an
     * admin, keyed on an id derived from the shared key.
     *
     * The room the ceremony ran in is somebody else's -- a NIP-17 room where
     * nobody administers anything, or the parent whose subgroup this is. This
     * gives the people who hold shares a room where every one of them can act,
     * which is the shape a group that has just made a t-of-n key is asking for.
     *
     * Derived rather than random, unlike every other Marmot room. Every member's
     * device can compute the id from the ceremony they all took part in, so the
     * room is addressable without being announced, and two members racing to create
     * it produce the same id instead of two rival rooms -- which is why this returns
     * early to the existing room rather than minting a second one.
     *
     * Only once the group has signed what the room signs with. That statement is
     * agreed in the room the ceremony ran in, before there is a room of its own
     * to hold it -- see `GroupKeyStateManager.propose` -- so by here it is a fact
     * the room is created *knowing*, rather than the first thing it has to go and
     * ask about.
     */
    fun createAdminGroup(onNavigateToRoute: (Route) -> Unit) {
        if (isActionPending.value) return

        val loaded = dkgRitualUIState as? DkgRitualUIState.Loaded ?: return
        val session = loaded.session ?: return
        val thresholdPublicKey = session.thresholdPublicKey ?: return

        // The screen offers nothing until the group has signed, so this is a
        // guard rather than a path anybody takes.
        if (!loaded.isKeyStateSigned) return

        val nostrPrivateKey = activeWalletStateFlow.value?.business?.walletManager?.keyManager?.value?.nostrPrivateKey()
        if (nostrPrivateKey == null) {
            dkgRitualUIState = DkgRitualUIState.Error("Couldn't read your keys. Please try again.")
            return
        }

        val keyPair = KeyPair(privKey = nostrPrivateKey.value.toByteArray())
        val groupId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

        // Everyone the ceremony was run with, this device included. These are the
        // admins: the whole point of the room is that the members who hold shares of
        // the key can all act in it.
        //
        // Off the ceremony rather than off the room, because for a subgroup the
        // room is the parent's and its roster is the wrong set entirely.
        val members = ChillDkgRitualManager.participantsOf(session, loaded.localChatRoom)

        // A subgroup is called what the coordinator called it; an admin room is
        // called after the group it administers, because it has no name of its
        // own to be given. Either way the bare name goes down and
        // `MarmotGroupCreation` puts it under `MarmotGroupName`, so a NIP-17 room
        // called `Ekklesia` stands up a Marmot room called `#Ekklesia`.
        val subgroupParent = loaded.parentChatRoomId

        val name = session.subject
            ?: if (subgroupParent != null) "Subgroup" else "Group"

        isActionPending.value = true
        dkgRitualUIState = loaded.copy(adminGroupBlockedOn = emptyList())

        viewModelScope.launch(Dispatchers.IO) {
            val outcome = chatRepository.createMarmotGroup(
                groupId = groupId,
                name = name,
                purpose = if (subgroupParent != null) {
                    "A subgroup of the group that certified it."
                } else {
                    "Admins of ${session.subject ?: "the group"}."
                },
                adminPublicKeys = members,
                userPublicKey = activeUserPublicKey,
                keyPair = keyPair,
                // Verified by the time it is written: the key state this room was
                // gated on carries the certificate, and `stateFrom` refused it
                // otherwise.
                parentChatRoomId = subgroupParent
            )

            isActionPending.value = false

            when (outcome) {
                is MarmotGroupCreation.Outcome.BlockedOn -> {
                    // Named rather than counted. A member cannot publish a key
                    // package on somebody else's behalf, so the only useful thing
                    // to say is whose door to knock on.
                    dkgRitualUIState = loaded.copy(
                        adminGroupBlockedOn = outcome.missing.map { publicKey ->
                            loaded.ritualMembers
                                .firstOrNull { it.participant.participantPublicKey == publicKey }
                                ?.profile
                                ?.humanReadableNameOrPubkey()
                                ?: publicKey.take(12)
                        }
                    )
                }

                is MarmotGroupCreation.Outcome.Failed -> {
                    dkgRitualUIState = DkgRitualUIState.Error(outcome.reason)
                }

                is MarmotGroupCreation.Outcome.Created -> withContext(Main) {
                    onNavigateToRoute(
                        ChatRoomMessagingRoute(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = outcome.room.chatRoom.id,
                            relayHint = null
                        )
                    )
                }

                is MarmotGroupCreation.Outcome.Existing -> withContext(Main) {
                    onNavigateToRoute(
                        ChatRoomMessagingRoute(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = outcome.room.chatRoom.id,
                            relayHint = null
                        )
                    )
                }
            }
        }
    }

    override fun onCleared() {
        messageObserver?.cancel()
        keyStateObserver?.cancel()
        certificateObserver?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "DkgRitualViewModel"

        /** Matches SelectChatRoomTypeViewModel: relays are not always prompt. */
        private const val KEY_PACKAGE_LOOKUP_TIMEOUT = 10_000L

        fun factory(
            chatRoomId: String,
            activeUserPublicKey: HexKey,
            dkgSessionId: String? = null,
            initialDkgRitualUIState: DkgRitualUIState = DkgRitualUIState.Loading,
            activeWalletStateFlow: StateFlow<ActiveWallet?>,
            chatRepository: ChatRepository,
            dkgRepository: DkgRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DkgRitualViewModel(
                    chatRoomId = chatRoomId,
                    activeUserPublicKey = activeUserPublicKey,
                    dkgSessionId = dkgSessionId,
                    initialDkgRitualUIState = initialDkgRitualUIState,
                    activeWalletStateFlow = activeWalletStateFlow,
                    chatRepository = chatRepository,
                    dkgRepository = dkgRepository
                )
            }
        }
    }
}
