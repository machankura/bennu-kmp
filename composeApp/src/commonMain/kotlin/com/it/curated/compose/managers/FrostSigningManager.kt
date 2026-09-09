package com.it.curated.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.utils.RandomInstance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.AggregatedNonce
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import fr.acinq.bitcoin.utils.Either
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import com.it.curated.compose.database.CuratedDatabase
import com.it.curated.compose.database.model.ChatMessage
import com.it.curated.compose.database.model.DkgSession
import com.it.curated.compose.database.model.FrostSignerMessage
import com.it.curated.compose.database.model.FrostSigningItem
import com.it.curated.compose.database.model.FrostSigningSession
import com.it.curated.compose.database.model.GiftWrapPayload
import com.it.curated.compose.database.model.GroupKeyState
import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.database.model.MarmotInnerEvent
import com.it.curated.compose.database.model.intermdiate.LocalChatRoom
import com.it.curated.compose.database.model.types.DkgRitualStage
import com.it.curated.compose.database.model.types.FrostSigningStage
import com.it.curated.compose.extensions.toHex
import com.it.curated.compose.nostr.dkg.DkgRitualEvents
import com.it.curated.compose.nostr.frost.FrostSigningEvents

/**
 * Signs a nostr event with a group's FROST threshold key, in one of its rooms.
 *
 * The shape is [ChillDkgRitualManager]'s, deliberately: the member who proposes
 * a signature coordinates it, protocol messages travel on the kinds in
 * [FrostSigningEvents], each inbound message is persisted and then the session
 * is asked whether it can move, and every step is recomputed from stored inputs
 * so a device killed mid-round resumes on the next message. What that manager's
 * own notes say about being message-driven applies here unchanged.
 *
 * ### Two transports, because a group signs before it has a room
 *
 * Almost every signature is made in the group's admin room, and there a
 * signing message is an ordinary Marmot inner event needing no addressing of its
 * own: the room already exists, its membership is exactly the share holders, and
 * its id is derived from the key. One encrypted group event reaches everyone.
 *
 * The exception is the group's *first* signature. A room's `GroupKeyState` is
 * now signed before the room it is about is created -- see
 * `GroupKeyStateManager.propose` -- so that session runs wherever the ceremony
 * ran: the members' NIP-17 room for a group's own key, where there is no MLS
 * tree and a message goes out as one sealed gift wrap per member, and the
 * parent's Marmot room for a subgroup's, where it is an ordinary group event.
 * [broadcast] is the only place that knows the difference; everything above it
 * is the same protocol either way.
 *
 * ### What it signs as
 *
 * The room's own key, not the group's root threshold key. A signing session is
 * created with the [SharedKeyDerivation] cache for the path the room was derived
 * at, so the `pubkey` on every event a room signs is that room's id -- see
 * [SharedKeyDerivation.marmotGroupId], where those are one value. A reader
 * holding a signed dialect therefore needs no lookup to check it: the author
 * they expect is the id of the room they found it in.
 *
 * A session in a ceremony's own room signs as the room that ceremony's key
 * derives -- the admin room that does not exist yet -- which is the same rule
 * read forwards: what a group signs as is the room the signature belongs to.
 *
 * The path comes from the room, never from a proposal -- [signingPath] -- because
 * it decides which key the group signs as.
 *
 * ### It signs a batch
 *
 * A session carries one or more events as [FrostSigningItem] rows, and runs one
 * FROST instance per event in lockstep: one signer set, one aggregate per item,
 * one partial signature per item per signer, one approval. Four group events
 * whatever the size, instead of four per event.
 *
 * That is all the batching there is, and all there can be. A Schnorr partial
 * signature is `s = k + e·x` with `e = H(R‖P‖m)`, so two messages under one
 * nonce give two equations in one unknown and the secret share falls out; a
 * batch shares only what is outside that equation. Every item has its own seed,
 * its own aggregate and its own `Session`, and [joinPayload] is the only place
 * they travel together.
 *
 * Three things are genuinely different from a ceremony, and each of them is why
 * this is a separate manager rather than another branch of that one.
 *
 * ### It does not need everybody
 *
 * A DKG cannot finish until every member takes part; that is what makes the key.
 * Signing with a t-of-n key needs t, and waiting for n would throw away the
 * property the group ran a ceremony to get. So the coordinator waits for the
 * threshold to be reachable, picks a signer set, and says who is in it. Members
 * outside the set do nothing and are not stalling anything.
 *
 * ### Restart-safety is forced, not chosen
 *
 * `SecretNonce` cannot be serialised and refuses to be used twice. Storing the
 * randomness it is derived from and regenerating on demand is the only way a
 * signing session can survive the app closing -- and it is safe only because an
 * item signs one message and cannot be made to sign another. See
 * [FrostSigningSession] for the two rules that hold that in place; both are
 * enforced here, in [acceptProposal] and in [record].
 *
 * ### One approval, not three
 *
 * A DKG asks three times because each step publishes something different and
 * commits the member to something different. Here every step serves one
 * decision -- sign these events or do not -- and they are fixed before the
 * member is asked. A second prompt would be the same question again. That holds
 * for a batch only as long as the member can see every event in it before
 * answering, which is the screen's side of the bargain.
 */
object FrostSigningManager {
    private const val TAG = "FrostSigningManager"

    private val logger = Logger.withTag(TAG)

    /**
     * The most events one session will sign.
     *
     * Enforced when proposing, and again -- independently -- when a proposal
     * arrives. The second check is the one that matters. A proposal is the only
     * place in this protocol where a remote party decides how much work everybody
     * else does: k native key generations, k signatures, and a group event
     * carrying k payloads, all from a single message. Until batching that was
     * bounded by never being more than one.
     *
     * Sized against what a group event will carry rather than picked round. A
     * signer's nonce message is k * 133 bytes of hex and commas and their partial
     * message k * 65, neither of which binds; the proposal carries k whole
     * events, which does.
     */
    const val MAX_BATCH_SIZE: Int = 64

    /**
     * Opens a signing session, making this device the coordinator.
     *
     * The event is signed as it stands apart from its `pubkey`, which is
     * replaced with the group's key: a signature over an event claiming
     * somebody else's author would verify against nothing.
     *
     * [key] names the ceremony to sign under, for the one caller that knows it
     * before the room does: `GroupKeyStateManager.propose`, whose whole job is to
     * establish the answer [completedKey] would otherwise look up -- a room
     * proposing its own key state cannot be asked which key it signs with.
     * Everybody else leaves it null and is resolved from the room, which is the
     * only form that cannot name a ceremony the room has nothing to do with.
     *
     * Even then it is taken only if this device actually holds a share of it, so
     * naming one cannot talk the session into signing with material it has not
     * got; anything else falls back as though none was given.
     */
    suspend fun proposeSigning(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String,
        key: DkgSession? = null,
        createdAt: Long = Clock.System.now().epochSeconds
    ): FrostSigningSession = proposeSigningBatch(
        database = database,
        localChatRoom = localChatRoom,
        userPublicKey = userPublicKey,
        events = listOf(EventTemplate<Event>(createdAt, kind, tags, content)),
        key = key
    )

    /**
     * Opens a session over several events at once, making this device the
     * coordinator.
     *
     * One ceremony, one signer set, one approval and four group events, whatever
     * the size -- but k independent FROST instances underneath, because that is
     * the only thing a batch can be. See [FrostSigningItem] for why.
     *
     * A batch is all-or-nothing: if any item cannot be aggregated the session
     * fails and none of its events are applied. That makes a batch **only as
     * available as its worst item**, so events that do not belong together
     * should not be proposed together.
     *
     * A failed batch is retried by proposing a *new* one, never by re-proposing
     * this session. Its items' nonce seeds have already been published against an
     * aggregate; reusing any of them for a second attempt would produce two
     * partial signatures over one secret nonce, which is how a share is
     * extracted. [itemsOver] mints fresh seeds precisely so that a retry is a new
     * session by construction.
     */
    suspend fun proposeSigningBatch(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<EventTemplate<*>>,
        key: DkgSession? = null
    ): FrostSigningSession = proposeSigningBatch(
        database = database,
        localChatRoom = localChatRoom,
        userPublicKey = userPublicKey,
        lead = events.firstOrNull()
            ?: throw IllegalArgumentException("A signing session must be given something to sign"),
        dependents = { events.drop(1) },
        key = key
    )

    /**
     * Opens a session over an event and the events that name it.
     *
     * The flat form above cannot express this. A chunk carries the id of the
     * chapter it belongs to, and that id is not knowable to a caller: it is a
     * hash over the *group's* key at the *room's* derivation path, and neither
     * is resolved until this function runs. A caller that computed it anyway
     * would be recomputing [signingPath], which is the one input in this
     * protocol that must never come from a proposer -- the path decides which
     * key the group signs as.
     *
     * So [lead] is built here and handed to [dependents], which returns the
     * events that reference it. Every id in the batch then comes from one place,
     * and an item naming a chapter nobody signed is not a mistake that can be
     * made rather than one that has to be tested for.
     *
     * The lead is item 0, so it is applied before anything that names it -- a
     * chunk row whose chapter does not exist yet is a foreign key violation.
     */
    suspend fun proposeSigningBatch(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        lead: EventTemplate<*>,
        dependents: (lead: Event) -> List<EventTemplate<*>>,
        key: DkgSession? = null
    ): FrostSigningSession {
        val ceremony = key?.takeIf { it.stage == DkgRitualStage.COMPLETE && it.secretShare != null }
            ?: completedKey(database, localChatRoom.chatRoom.id)
            ?: throw IllegalStateException("This group has no shared key to sign with")

        val signerId = signerIdOf(database, ceremony, userPublicKey)
            ?: throw IllegalStateException("This device is not a participant in ceremony ${ceremony.id}")

        val path = signingPath(database, localChatRoom, ceremony)
        val unsignedEventOf = { template: EventTemplate<*> ->
            unsignedEventOf(
                key = ceremony,
                path = path,
                kind = template.kind,
                tags = template.tags,
                content = template.content,
                createdAt = template.createdAt
            )
        }

        val leadEvent = unsignedEventOf(lead)
        val unsignedEvents = listOf(leadEvent) + dependents(leadEvent).map(unsignedEventOf)

        require(unsignedEvents.size <= MAX_BATCH_SIZE) {
            "A signing session will sign at most $MAX_BATCH_SIZE events, not ${unsignedEvents.size}"
        }

        val sessionId = RandomInstance.bytes(32).toHex()

        val session = FrostSigningSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            dkgSessionId = ceremony.id,
            threshold = ceremony.threshold,
            participantCount = ceremony.participantCount,
            signerId = signerId,
            derivationPath = path?.let(SharedKeyDerivation::formatPath),
            // Proposing a signature is already the act of agreeing to it.
            signApprovedAt = Clock.System.now()
        )
        database.frostSigningSessionDao().upsert(session)
        database.frostSigningSessionDao().upsertItems(itemsOver(sessionId, unsignedEvents))
        announceStarted(database, session)

        logger.i(
            "Proposing signature $sessionId over ${describe(unsignedEvents.size)} " +
                "(${unsignedEvents.joinToString { it.id.take(8) }}) with key ${ceremony.id}"
        )

        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.PROPOSAL,
            content = FrostSigningEvents.encodeProposal(unsignedEvents),
            includeKey = true
        )

        advance(database, localChatRoom, sessionId)

        return session
    }

    /**
     * Feeds one inbound signing message in and advances the session as far as it
     * will go. Safe to call twice with the same message: every write is keyed
     * and every step recomputed from stored inputs.
     */
    suspend fun processSigningPayload(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey
    ) {
        val sessionId = FrostSigningEvents.parseSessionId(innerEvent.tags)
        if (sessionId == null) {
            logger.w("FROST payload ${innerEvent.id} has no session tag; dropping")
            return
        }

        val session = if (innerEvent.kind == FrostSigningEvents.PROPOSAL) {
            acceptProposal(
                database = database,
                localChatRoom = localChatRoom,
                innerEvent = innerEvent,
                sessionId = sessionId,
                userPublicKey = userPublicKey
            )
        } else {
            // Not knowing the session is normal: a member catching up applies a
            // group's backlog in whatever order the epochs decrypt, so a nonce can
            // land before the proposal that asks for it. The inner event is already
            // stored, and [acceptProposal] replays it once the proposal turns up.
            database.frostSigningSessionDao().getSessionById(sessionId)
        }

        if (session == null) {
            logger.i("No signing session $sessionId for kind ${innerEvent.kind}; leaving it stored")
            return
        }

        if (session.stage == FrostSigningStage.FAILED) {
            logger.i("Session $sessionId already failed; ignoring kind ${innerEvent.kind}")
            return
        }

        if (!record(database, session, innerEvent)) return

        advance(database, localChatRoom, session.id)
    }

    /**
     * Records a proposal. Returns the session, whether it was just created or
     * already known.
     *
     * Publishes nothing: a signature is the one thing a group's key exists to
     * produce, so a relay delivering an event to a phone in a pocket must not be
     * enough to make it happen.
     */
    private suspend fun acceptProposal(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey
    ): FrostSigningSession? {
        database.frostSigningSessionDao().getSessionById(sessionId)?.let { existing ->
            // The events a session signs are fixed at creation. A second proposal
            // under the same id carrying different ones is either a mistake or an
            // attempt to get two signatures out of one secret nonce, which is how
            // a share is extracted -- so it is refused, not applied.
            val proposed = FrostSigningEvents.decodeProposal(innerEvent.content)
            val signing = database.frostSigningSessionDao().getItems(sessionId).map { it.eventId }
            if (proposed != null && signing != proposed.map { it.id }) {
                logger.w(
                    "Session $sessionId re-proposed with ${describe(proposed.size)} " +
                        "(${proposed.joinToString { it.id.take(8) }}), but it is already signing " +
                        "${signing.joinToString { it.take(8) }}; ignoring"
                )
            }
            return existing
        }

        val dkgSessionId = FrostSigningEvents.parseKey(innerEvent.tags)
        if (dkgSessionId == null) {
            logger.w("Signing proposal $sessionId names no key; dropping")
            return null
        }

        val key = database.dkgSessionDao().getSessionById(dkgSessionId)
        if (key == null || key.stage != DkgRitualStage.COMPLETE) {
            logger.w("Signing proposal $sessionId names key $dkgSessionId, which this device does not hold; dropping")
            return null
        }

        val signerId = signerIdOf(database, key, userPublicKey)
        if (signerId == null) {
            logger.w("Signing proposal $sessionId is for a ceremony this device did not take part in; dropping")
            return null
        }

        val proposed = FrostSigningEvents.decodeProposal(innerEvent.content)
        if (proposed == null) {
            logger.w("Signing proposal $sessionId does not carry any events; dropping")
            return null
        }

        // Checked here as well as when proposing, because this is where a remote
        // party gets to decide how much work this device does. See [MAX_BATCH_SIZE].
        if (proposed.size > MAX_BATCH_SIZE) {
            logger.w(
                "Signing proposal $sessionId asks for ${proposed.size} events, " +
                    "more than the $MAX_BATCH_SIZE this device will sign at once; dropping"
            )
            return null
        }

        // Rebuilt from the event's own fields rather than trusted. The id is the
        // 32 bytes every signer puts their share behind, so taking the proposer's
        // word for it would let them have the group sign one thing while being
        // shown another.
        //
        // The author it is rebuilt under comes from this device's own reading of
        // the room, for the same reason. A proposer who could choose the path
        // could choose the key the group signs as, and every signer would put
        // their share behind an author none of them checked.
        val path = signingPath(database, localChatRoom, key)
        val unsignedEvents = proposed.map { event ->
            unsignedEventOf(
                key = key,
                path = path,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                createdAt = event.createdAt
            )
        }

        // All of them or none. A batch's length is what every later payload is
        // checked against, so quietly dropping one bad event would leave a session
        // that rejects every signer's contribution for being the wrong size --
        // a stall with nothing to blame it on.
        unsignedEvents.forEachIndexed { index, rebuilt ->
            if (rebuilt.id != proposed[index].id) {
                logger.w(
                    "Signing proposal $sessionId carries id ${proposed[index].id} at $index " +
                        "but its fields hash to ${rebuilt.id}; dropping"
                )
                return null
            }
        }

        val session = FrostSigningSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            // Whoever proposes coordinates. Aggregating nonces and partial
            // signatures gives no power over the outcome -- a wrong aggregate
            // produces a signature that does not verify, not a forged one.
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            dkgSessionId = key.id,
            threshold = key.threshold,
            participantCount = key.participantCount,
            signerId = signerId,
            derivationPath = path?.let(SharedKeyDerivation::formatPath)
        )
        database.frostSigningSessionDao().upsert(session)
        database.frostSigningSessionDao().upsertItems(itemsOver(sessionId, unsignedEvents))
        announceStarted(database, session)

        logger.i(
            "Recorded signing session $sessionId over ${describe(unsignedEvents.size)} " +
                "(${unsignedEvents.joinToString { it.id.take(8) }}); awaiting approval"
        )

        announceApprovalNeeded(database, session)
        replayStoredMessages(database, localChatRoom, session)

        return session
    }

    /**
     * Feeds in every message of this session that arrived before the proposal did.
     *
     * They were dropped at the time for want of a session to file them under, but
     * the inbound path stores every payload it decrypts before dispatching on
     * kind, so nothing was actually lost — this reads them back out.
     *
     * Out of whichever store the room's transport writes to, which has to be the
     * same reading [broadcast] makes: a session in a NIP-17 room has its backlog
     * in the gift-wrap payloads and none at all in the inner events, and looking
     * in the wrong one is a stall with nothing to blame it on.
     */
    private suspend fun replayStoredMessages(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession
    ) {
        val kinds = FrostSigningEvents.ALL.toList()

        val received = if (localChatRoom.chatRoom.mlsGroupState != null) {
            database.marmotInnerEventDao()
                .getByChatRoomAndKinds(chatRoomId = session.chatRoomId, kinds = kinds)
                .map { stored ->
                    Event(
                        id = stored.id,
                        pubKey = stored.publicKey,
                        createdAt = stored.createdAt.epochSeconds,
                        kind = stored.kind,
                        tags = stored.tags,
                        content = stored.content,
                        sig = ""
                    )
                }
        } else {
            database.giftWrapPayloadDao()
                .getByChatRoomAndKinds(chatRoomId = session.chatRoomId, kinds = kinds)
                .map { stored ->
                    Event(
                        id = stored.id,
                        pubKey = stored.publicKey,
                        createdAt = stored.createdAt.epochSeconds,
                        kind = stored.kind,
                        tags = stored.tags,
                        content = stored.content,
                        sig = ""
                    )
                }
        }

        val stored = received.filter { stored ->
            stored.kind != FrostSigningEvents.PROPOSAL &&
                FrostSigningEvents.parseSessionId(stored.tags) == session.id
        }

        if (stored.isEmpty()) return

        logger.i("Replaying ${stored.size} stored message(s) for signing session ${session.id}")

        stored.forEach { innerEvent ->
            if (!record(database, session, innerEvent)) return
        }
    }

    /**
     * Files one signing message. Returns false when the message ends the session,
     * so the caller stops rather than trying to advance a dead one.
     */
    private suspend fun record(
        database: CuratedDatabase,
        session: FrostSigningSession,
        innerEvent: Event
    ): Boolean {
        when (innerEvent.kind) {
            FrostSigningEvents.PROPOSAL -> Unit // handled by acceptProposal

            FrostSigningEvents.NONCE,
            FrostSigningEvents.PARTIAL_SIGNATURE -> {
                val known = database.frostSigningSessionDao()
                    .getMessage(session.id, innerEvent.kind, innerEvent.pubKey) != null

                database.frostSigningSessionDao().upsert(
                    FrostSignerMessage(
                        sessionId = session.id,
                        signerPublicKey = innerEvent.pubKey,
                        kind = innerEvent.kind,
                        payload = innerEvent.content
                    )
                )

                if (!known) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            FrostSigningEvents.SIGNER_SET -> {
                if (!isFromCoordinator(session, innerEvent)) return true

                val signerIds = FrostSigningEvents.parseSignerIds(innerEvent.tags)
                if (signerIds == null) {
                    logger.w("Session ${session.id}: signer set carries no ids; ignoring")
                    return true
                }

                // Write-once, and this is the load-bearing one. Signing the same
                // message twice under one secret nonce against two different
                // aggregated nonces is exactly how a secret share is extracted, so
                // a coordinator that sends a second, different signer set is
                // ignored rather than obeyed. The session stalls; the share does
                // not leak.
                //
                // `signerIds` is the gate for the whole batch: it and every item's
                // aggregated nonce are written together, so a session holding one
                // holds all of them.
                if (current(database, session).signerIds != null) return true

                val aggregated = splitForSession(database, session, innerEvent.content)
                    ?: return true

                if (applyAggregate(database, session, signerIds, aggregated)) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            FrostSigningEvents.SIGNATURE -> {
                if (!isFromCoordinator(session, innerEvent)) return true

                // Write-once as a unit, for the same reason the aggregate is: a
                // batch that had some of its signatures would be one the group
                // could neither finish nor safely retry.
                if (isSigned(database, session)) return true

                val signatures = splitForSession(database, session, innerEvent.content)
                    ?: return true

                if (applySignatures(database, session, signatures)) {
                    announceStep(database, session, innerEvent.kind, innerEvent.pubKey)
                }
            }

            FrostSigningEvents.FAILURE -> {
                fail(
                    database = database,
                    session = session,
                    reason = "Abandoned by ${innerEvent.pubKey.take(8)}: ${innerEvent.content}",
                    culprit = innerEvent.pubKey
                )
                return false
            }
        }

        return true
    }

    private fun isFromCoordinator(
        session: FrostSigningSession,
        innerEvent: Event
    ): Boolean {
        if (innerEvent.pubKey == session.coordinatorPublicKey) return true

        logger.w(
            "Session ${session.id}: kind ${innerEvent.kind} from " +
                "${innerEvent.pubKey.take(8)}, who is not the coordinator; ignoring"
        )
        return false
    }

    /**
     * Takes every step the stored messages now allow, in order, stopping at the
     * first one still waiting on somebody.
     *
     * Each step asks whether its output already exists rather than whether the
     * stage says it has run, so re-entering after a crash — or after the same
     * message is delivered twice — repeats no work and skips none.
     */
    private suspend fun advance(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String
    ) {
        var session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return
        if (session.stage == FrostSigningStage.COMPLETE || session.stage == FrostSigningStage.FAILED) return

        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return
        val secretShare = key.secretShare?.let { PrivateKey(ByteVector32(it)) } ?: return
        val thresholdPublicKey = key.thresholdPublicKey ?: return

        try {
            // A signature the group has already made settles this session whether
            // or not its owner ever answered: a t-of-n key does not need everybody,
            // so a quorum can finish while one member's phone is still in a pocket.
            // Ahead of the gate below because that gate is about keeping this
            // device's own material off the wire, and finishing puts none of it
            // there -- while going on to ask would be asking for a decision that
            // can no longer change anything, and would let a late "don't sign"
            // abandon a signature that exists.
            if (isSigned(database, session)) {
                complete(database, session)
                return
            }

            // Nothing of this device's own goes out before its owner has said so.
            // Returns rather than throws: the session is not failing, it is waiting
            // on a person, and everything received stays stored so it resumes the
            // moment they approve.
            if (session.signApprovedAt == null) {
                announceApprovalNeeded(database, session)
                return
            }

            // The room's key rather than the group's root key. A signature
            // aggregates against whatever the cache carries, so the cache and the
            // author on the event have to be the same derivation -- and the
            // session stores the path exactly so that every pass rebuilds the
            // same one.
            val tweakCache = SharedKeyDerivation
                .derive(thresholdPublicKey, session.pathIndices())
                .cache
            val publicShares = key.publicShareList()

            // The events this session signs, in the order the proposal fixed. Every
            // join below is positional against it.
            val items = database.frostSigningSessionDao().getItems(sessionId)
            if (items.isEmpty()) return

            val messages = items.map { ByteVector(it.eventId.hexToByteArray()) }

            val ownNonces = ownMessage(database, session, FrostSigningEvents.NONCE)
            val ownPartials = ownMessage(database, session, FrostSigningEvents.PARTIAL_SIGNATURE)

            // One nonce per item, regenerated rather than stored -- SecretNonce
            // refuses both. Safe because an item's message can never change; see
            // the notes on FrostSigningSession.
            //
            // On demand rather than up front, and that is the point of the `lazy`:
            // `advance` runs on every arriving message, so at a batch of k this is
            // k native key generations each time, usually to find there is nothing
            // left to publish. A guard computed here instead would have to predict
            // whether this device turns out to be a signer, which it cannot -- the
            // coordinator settles the signer set further down this same pass.
            val nonces by lazy {
                items.mapIndexed { index, item ->
                    SecretNonce.generate(
                        sessionRandom = ByteVector32(item.nonceRandom),
                        secretShare = secretShare,
                        publicShare = publicShares?.getOrNull(session.signerId),
                        tweakedThresholdPublicKey = tweakCache.tweakedPublicKey,
                        message = messages[index],
                        extraInput = null
                    )
                }
            }

            if (ownNonces == null) {
                publishOwn(
                    database,
                    localChatRoom,
                    session,
                    FrostSigningEvents.NONCE,
                    joinPayload(nonces.map { (_, publicNonce) -> publicNonce.data.toHex() })
                )
            }

            if (session.isCoordinator() && session.signerIds == null) {
                val offered = orderedNonces(database, session, items.size) ?: return
                val chosen = offered.take(session.threshold)
                val chosenIds = chosen.map { (id, _) -> id }

                // One aggregate per item, each built from that item's nonce from
                // each chosen signer. Reusing one across two items would be reusing
                // R across two messages, which is the whole thing this design is
                // arranged to make impossible.
                val aggregated = items.indices.map { index ->
                    IndividualNonce.aggregate(chosen.map { (_, offeredNonces) -> offeredNonces[index] })
                        .orThrow("aggregating nonces")
                        .toByteArray()
                        .toHex()
                }

                if (!applyAggregate(database, session, chosenIds, aggregated)) return
                session = current(database, session)

                broadcast(
                    database = database,
                    localChatRoom = localChatRoom,
                    session = session,
                    kind = FrostSigningEvents.SIGNER_SET,
                    content = joinPayload(aggregated),
                    signerIds = chosenIds
                )
                announceStep(
                    database,
                    session,
                    FrostSigningEvents.SIGNER_SET,
                    session.userPublicKey
                )
            }

            val signerIds = session.signerIdList() ?: return
            // Re-read, because the aggregate above is written to the item rows.
            val aggregated = database.frostSigningSessionDao().getItems(sessionId)
            session = moveTo(database, session, FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)

            // A member outside the chosen set has nothing to contribute and is not
            // holding anybody up. They stay in the session to receive the finished
            // signatures like everybody else -- and leaving here rather than
            // building k FROST sessions to do nothing with is the same saving the
            // nonce short-circuit above makes.
            val signing = session.isSigner() && ownPartials == null
            val aggregating = session.isCoordinator() && !isSigned(database, session)
            if (!signing && !aggregating) {
                complete(database, session)
                return
            }

            // One FROST session per item. `Session.create` binds the message and
            // the aggregate together, so only the signer set, the shares and the
            // tweak cache are shared across a batch.
            val signingSessions = aggregated.mapIndexed { index, item ->
                Session.create(
                    aggregatedNonce = AggregatedNonce(
                        (item.aggregatedNonce ?: return).hexToByteArray()
                    ),
                    signerIds = signerIds.map { it.toUInt() },
                    signerPublicShares = publicShares?.let { shares ->
                        signerIds.mapNotNull { shares.getOrNull(it) }.takeIf { it.size == signerIds.size }
                    },
                    nParticipants = session.participantCount,
                    threshold = session.threshold,
                    tweakCache = tweakCache,
                    message = messages[index]
                )
            }

            if (signing) {
                val partialSignatures = signingSessions.mapIndexed { index, signingSession ->
                    signingSession
                        .sign(nonces[index].first, secretShare, session.signerId.toUInt())
                        .orThrow("signing")
                        .toHex()
                }

                publishOwn(
                    database,
                    localChatRoom,
                    session,
                    FrostSigningEvents.PARTIAL_SIGNATURE,
                    joinPayload(partialSignatures)
                )
            }

            if (aggregating) {
                val partials = orderedPartialSignatures(database, session, signerIds, aggregated.size)
                    ?: return

                val signatures = signingSessions.mapIndexed { index, signingSession ->
                    signingSession
                        .aggregateSigs(partials.map { ByteVector32(it[index]) })
                        .orThrow("aggregating partial signatures")
                        .toHex()
                }

                if (!applySignatures(database, session, signatures)) return
                session = current(database, session)

                broadcast(
                    database = database,
                    localChatRoom = localChatRoom,
                    session = session,
                    kind = FrostSigningEvents.SIGNATURE,
                    content = joinPayload(signatures)
                )
                announceStep(database, session, FrostSigningEvents.SIGNATURE, session.userPublicKey)
            }

            complete(database, session)
        } catch (e: CancellationException) {
            // The sync was torn down mid-step, which says nothing about the session.
            throw e
        } catch (e: Throwable) {
            logger.e("Signing session $sessionId failed", e)
            fail(database, session, e.message ?: e::class.simpleName ?: "Unknown error")
            broadcast(
                database = database,
                localChatRoom = localChatRoom,
                session = session,
                kind = FrostSigningEvents.FAILURE,
                content = e.message ?: "Signing failed"
            )
        }
    }

    /**
     * Verifies the group's signature, uses the event, and closes the session.
     *
     * The payoff: a signature that verifies is one the group made, whoever
     * relayed it. Checking rather than trusting is what keeps a faulty or
     * dishonest coordinator from passing off something that will be rejected by
     * every relay it reaches.
     *
     * Reached only with the session still open -- [advance] returns above this
     * on a settled one -- so the milestone is written once by construction,
     * like every other line in the transcript.
     */
    private suspend fun complete(database: CuratedDatabase, session: FrostSigningSession) {
        val items = database.frostSigningSessionDao().getItems(session.id)
        if (items.isEmpty() || items.any { !it.isSigned() }) return

        // Every signature is checked before any event is applied. A batch is
        // all-or-nothing, so a bad one anywhere has to fail the session rather
        // than leave some of its events already filed.
        val signedEvents = items.map { item ->
            val signedEvent = signedEvent(item, item.signature!!)
            val verified = Nip01Crypto.verify(
                signature = item.signature.hexToByteArray(),
                hash = item.eventId.hexToByteArray(),
                pubKey = signedEvent.pubKey.hexToByteArray()
            )
            if (!verified) {
                throw IllegalStateException(
                    "The aggregated signature does not verify against ${item.eventId}"
                )
            }
            signedEvent
        }

        update(database, session) { it.copy(stage = FrostSigningStage.COMPLETE) }

        // The group's own statement, filed before anything is derived from it.
        // What the batch is applied *into* is a reading of these events -- an
        // artifact keeps some of its fields, a contributor list keeps none --
        // and the reading is the part that can be wrong or, for a kind this
        // build has no arm for, missing entirely. So the events are kept as
        // signed, and the rows made from them point back at these.
        val recorded = recordSignedEvents(database, session, signedEvents)

        // A signature exists to be used. Every device has the event and the
        // signature by now, so each applies the result itself rather than
        // waiting to be sent something it can already build -- the same
        // reasoning the transcript lines are written on. Nothing goes on the
        // wire: a signed event authored by the threshold key cannot travel as
        // an inner event anyway, because the outbound pipeline re-authors
        // rumors as their sender and would strip the group's signature off.
        signedEvents.forEach { applySignedEvent(database, session, it, recorded) }

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_COMPLETE,
            content = "The group signed ${describe(signedEvents.size)}. It took " +
                "${session.threshold} of ${session.participantCount} members.",
            actor = session.coordinatorPublicKey
        )

        logger.i("Signing session ${session.id} complete")
    }

    /**
     * Files the batch as [GroupSignedEvent] rows, and says whether it landed.
     *
     * One write for the batch, because that is how it was signed: a session's
     * events are one decision by one quorum, and half of them on file is a state
     * no reader should have to reason about. The path comes from the session
     * rather than from the events, since it is the session that resolved it from
     * the room -- see [signingPath], and note that a null there means the
     * untweaked threshold key rather than an unknown path.
     *
     * A failure is logged and swallowed, like [applySignedEvent]'s: the
     * signature is made and valid either way, and a ceremony that succeeded must
     * not be reported as failed because this device could not write it down.
     * What the caller loses is the id to point the derived rows at, which is why
     * this returns a boolean rather than nothing.
     */
    private suspend fun recordSignedEvents(
        database: CuratedDatabase,
        session: FrostSigningSession,
        signedEvents: List<Event>
    ): Boolean = try {
        database.groupSignedEventDao().recordAll(
            signedEvents.map { signedEvent ->
                GroupSignedEvent.fromEvent(
                    event = signedEvent,
                    chatRoomId = session.chatRoomId,
                    derivationPath = session.derivationPath,
                    frostSigningSessionId = session.id,
                )
            }
        )
        true
    } catch (e: Throwable) {
        logger.e("Signed session ${session.id} but could not record its events", e)
        false
    }

    /**
     * Turns the signed event into whatever it is: a dialect, an artifact, a
     * chapter.
     *
     * Reuses the inbound path's dispatch rather than repeating it, with no group
     * event and no inner event behind the row -- there is neither, and both
     * columns are nullable for exactly this kind of locally-derived record. What
     * the row does get is the [GroupSignedEvent] it was made from, when
     * [recorded] says one is on file; a row pointing at an event that is not
     * there would be worse than one pointing at nothing.
     *
     * A failure here is not the session's: the signature is made and valid, and
     * saying otherwise would tell the group to abandon a ceremony that
     * succeeded. It is logged and the session still completes.
     */
    private suspend fun applySignedEvent(
        database: CuratedDatabase,
        session: FrostSigningSession,
        signedEvent: Event,
        recorded: Boolean
    ) {
        try {
            ChatMessage.applyInnerEvent(
                database = database,
                groupId = session.chatRoomId,
                event = signedEvent,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                senderPublicKey = session.coordinatorPublicKey,
                isUserMessage = session.isCoordinator(),
                createdAt = Clock.System.now(),
                groupSignedEventId = signedEvent.id.takeIf { recorded }
            )?.let { database.chatMessageDao().upsert(it) }
        } catch (e: Throwable) {
            logger.e("Signed ${signedEvent.id} but could not apply it locally", e)
        }
    }

    /**
     * The event this session produces, with the signature on it.
     *
     * Public so a caller can take the finished event and do whatever it was
     * signing it for — the session's job ends at a valid signature.
     */
    fun signedEvent(item: FrostSigningItem, signature: HexKey): Event {
        val unsigned = Event.fromJson(item.unsignedEventJson)

        return Event(
            id = unsigned.id,
            pubKey = unsigned.pubKey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = signature
        )
    }

    /** The finished event, or null while this item is still running. */
    fun signedEvent(item: FrostSigningItem): Event? =
        item.signature?.let { signedEvent(item, it) }

    /** Every finished event of a batch, empty while any of them is still running. */
    fun signedEvents(items: List<FrostSigningItem>): List<Event> =
        items.map { it.signature ?: return emptyList() }
            .mapIndexed { index, signature -> signedEvent(items[index], signature) }

    /**
     * The items a session signs: one per event, each with its own nonce seed.
     *
     * Independent seeds rather than one derived per index, which would work and
     * save nothing worth having. Independence means an off-by-one anywhere in
     * the index handling produces a session that fails to aggregate, instead of
     * one that signs two messages under a single nonce.
     */
    private fun itemsOver(sessionId: String, events: List<Event>): List<FrostSigningItem> =
        events.mapIndexed { index, event ->
            FrostSigningItem(
                sessionId = sessionId,
                itemIndex = index,
                unsignedEventJson = event.toJson(),
                eventId = event.id,
                nonceRandom = RandomInstance.bytes(32).toHex()
            )
        }

    /**
     * Records the coordinator's signer set and the aggregated nonce each item is
     * signed against, as one write. Returns false when the message does not fit
     * the batch, so the caller neither announces nor acts on it.
     *
     * The two belong together. [FrostSigningSession.signerIds] is what the rest
     * of the session gates on, so a session holding it while an item still had no
     * aggregate would stall on a message that has already been delivered — and
     * the write that cleared the stall would be a second aggregate for an item
     * that had one, which is the case the write-once rule exists to prevent.
     * Items first, in one transaction, then the session: the gate is only ever
     * set on a batch that is entirely ready.
     */
    private suspend fun applyAggregate(
        database: CuratedDatabase,
        session: FrostSigningSession,
        signerIds: List<Int>,
        aggregatedNonces: List<HexKey>
    ): Boolean {
        val items = database.frostSigningSessionDao().getItems(session.id)
        if (items.isEmpty() || items.size != aggregatedNonces.size) {
            logger.w(
                "Session ${session.id}: signer set carries ${aggregatedNonces.size} " +
                    "aggregated nonce(s) for ${items.size} item(s); ignoring"
            )
            return false
        }

        database.frostSigningSessionDao().upsertItems(
            items.mapIndexed { index, item -> item.copy(aggregatedNonce = aggregatedNonces[index]) }
        )
        update(database, session) { it.copy(signerIds = signerIds.joinToString(",")) }

        return true
    }

    /**
     * Records the group's finished signatures, all of them or none. Returns false
     * when the message does not fit the batch.
     *
     * Settled as a unit for the same reason the aggregate is: a batch holding
     * some of its signatures is one the group can neither finish nor safely
     * retry, since a retry means fresh nonces for events that already have a
     * signature against the old ones.
     */
    private suspend fun applySignatures(
        database: CuratedDatabase,
        session: FrostSigningSession,
        signatures: List<HexKey>
    ): Boolean {
        val items = database.frostSigningSessionDao().getItems(session.id)
        if (items.isEmpty() || items.size != signatures.size) {
            logger.w(
                "Session ${session.id}: ${signatures.size} signature(s) for " +
                    "${items.size} item(s); ignoring"
            )
            return false
        }

        database.frostSigningSessionDao().upsertItems(
            items.mapIndexed { index, item -> item.copy(signature = signatures[index]) }
        )

        return true
    }

    /**
     * Whether the group has produced every signature this session was opened for.
     *
     * Counted rather than flagged, so it cannot disagree with the rows it
     * describes. A session with no items is not signed — it is one whose proposal
     * has not landed yet.
     */
    private suspend fun isSigned(database: CuratedDatabase, session: FrostSigningSession): Boolean {
        val total = database.frostSigningSessionDao().countItems(session.id)

        return total > 0 && database.frostSigningSessionDao().countSignedItems(session.id) == total
    }

    /** "the event" or "3 events", for a transcript line that reads the same at either size. */
    private fun describe(count: Int): String = if (count == 1) "the event" else "$count events"

    /**
     * The nonces on offer, as (signer id, nonce), ordered by signer id — or null
     * while fewer than the threshold have arrived.
     *
     * Ordered so that every device that recomputes the aggregate from the same
     * set arrives at the same bytes. FROST binds the signer set into the
     * challenge, so an order nobody else derives is a signature nobody can
     * aggregate.
     */
    private suspend fun orderedNonces(
        database: CuratedDatabase,
        session: FrostSigningSession,
        items: Int
    ): List<Pair<Int, List<IndividualNonce>>>? {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return null
        val idByMember = signerIds(database, key)

        val offered = database.frostSigningSessionDao()
            .getMessagesByKind(session.id, FrostSigningEvents.NONCE)
            .mapNotNull { message ->
                val id = idByMember[message.signerPublicKey] ?: return@mapNotNull null
                val values = splitPayload(message.payload, items) ?: run {
                    logger.w(
                        "Session ${session.id}: ${message.signerPublicKey.take(8)} offered a " +
                            "nonce payload that is not $items value(s); leaving them out"
                    )
                    return@mapNotNull null
                }

                id to values.map { IndividualNonce(it.hexToByteArray()) }
            }
            .sortedBy { (id, _) -> id }

        if (offered.size < session.threshold) {
            logger.d("Session ${session.id}: ${offered.size}/${session.threshold} nonces")
            return null
        }

        return offered
    }

    /**
     * The chosen signers' partial signatures in signer-set order, or null while
     * any are missing. Aggregation needs exactly one per signer, positionally
     * matched to the set the session was created with.
     */
    private suspend fun orderedPartialSignatures(
        database: CuratedDatabase,
        session: FrostSigningSession,
        signerIds: List<Int>,
        items: Int
    ): List<List<ByteArray>>? {
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return null
        val memberById = signerIds(database, key).entries.associate { (member, id) -> id to member }

        val payloadByMember = database.frostSigningSessionDao()
            .getMessagesByKind(session.id, FrostSigningEvents.PARTIAL_SIGNATURE)
            .associate { it.signerPublicKey to it.payload }

        val partials = signerIds.mapNotNull { id ->
            val payload = memberById[id]?.let { payloadByMember[it] } ?: return@mapNotNull null

            splitPayload(payload, items)?.map { it.hexToByteArray() }
        }

        if (partials.size < signerIds.size) {
            logger.d("Session ${session.id}: ${partials.size}/${signerIds.size} partial signatures")
            return null
        }

        return partials
    }

    /**
     * A signer's whole contribution to a batch, as one payload.
     *
     * Comma separated, the encoding [FrostSigningSession.signerIds] already uses,
     * and at a batch of one it is the bare value — which is what keeps a
     * single-event session on exactly the wire it has always been on.
     *
     * One row per signer per kind rather than one per item, deliberately.
     * [FrostSignerMessage]'s key is what makes a redelivered message replace its
     * predecessor instead of adding a row, and splitting by item would multiply
     * the ways a partial delivery can look like a complete one.
     */
    private fun joinPayload(values: List<HexKey>): String = values.joinToString(",")

    /**
     * The other half, and strict: a payload not carrying exactly [expected]
     * values is refused rather than truncated or padded.
     *
     * Checked here rather than in [record] on purpose. Payloads are stored
     * without being parsed, which is what lets a nonce arrive before the proposal
     * that would give it a length to be checked against. This runs where the
     * session — and so the length — is known.
     */
    private fun splitPayload(payload: String, expected: Int): List<HexKey>? =
        payload.split(",").map { it.trim() }.takeIf { it.size == expected }

    /** [splitPayload] against the number of events this session signs. */
    private suspend fun splitForSession(
        database: CuratedDatabase,
        session: FrostSigningSession,
        payload: String
    ): List<HexKey>? {
        val expected = database.frostSigningSessionDao().countItems(session.id)

        return splitPayload(payload, expected) ?: run {
            logger.w(
                "Session ${session.id}: payload carries ${payload.split(",").size} value(s) " +
                    "for $expected item(s); ignoring"
            )
            null
        }
    }

    /**
     * Every ceremony participant's FROST id, keyed by their nostr public key.
     *
     * The id is the member's index in the ceremony's participant order, which is
     * the bytewise sort of the host public keys — the same ordering ChillDKG
     * hashed into the session identity, and so the same one the public shares
     * are in. Deriving it rather than storing it means signing cannot disagree
     * with the ceremony that made the key.
     */
    private suspend fun signerIds(
        database: CuratedDatabase,
        key: DkgSession
    ): Map<HexKey, Int> {
        val hostKeys = database.dkgSessionDao()
            .getMessagesByKind(key.id, DkgRitualEvents.HOST_KEY)
            .associate { it.participantPublicKey to it.payload.lowercase() }

        val order = hostKeys.values.sorted()

        return hostKeys.mapNotNull { (member, hostKey) ->
            order.indexOf(hostKey).takeIf { it >= 0 }?.let { member to it }
        }.toMap()
    }

    private suspend fun signerIdOf(
        database: CuratedDatabase,
        key: DkgSession,
        member: HexKey
    ): Int? = signerIds(database, key)[member]

    /**
     * The key a room signs with, or null when it has none.
     *
     * Signing usually runs in the admin room, which is never where the ceremony
     * ran: the admin room's id is derived from the key the ceremony produces, so
     * it cannot exist until afterwards. A group's own ceremony runs in the NIP-17
     * room its members share; a subgroup's runs in its parent's Marmot room.
     *
     * They are bound together by the room's [GroupKeyState]: the statement the
     * room opened with and the group signed, naming the ceremony behind it. That
     * is a lookup rather than a search, and it carries the derivation path, so a
     * room derived anywhere other than [SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH]
     * is findable at all -- which the rederivation below cannot manage, since it
     * can only rederive at the one path the constant names.
     *
     * The state buys none of its authority from being written down. It is only
     * ever stored having rederived the room it describes, so what actually binds
     * a room to a key is still that the room's id *is* the key, and a room still
     * cannot be pointed at a key it was not derived from.
     *
     * Two fallbacks behind it. The first is the original scan over every
     * ceremony this device holds a share for, rederiving each one's room id
     * until one matches; the second is a ceremony held in this very room, which
     * is not how the app wires things today but costs one lookup to keep honest.
     *
     * That second one asks for the room's *own* ceremony, and the distinction is
     * load-bearing now rather than pedantic. A parent's Marmot room hosts the
     * ChillDKG of every subgroup it makes, so it holds completed ceremonies whose
     * key belongs to a child; handing one back here would have the parent sign as
     * its own subgroup, silently, for every member who holds no key-state row --
     * which is every member welcomed after the parent's own ceremony. A ceremony
     * naming a parent is never the room's own key, and that is all it takes to
     * tell them apart.
     *
     * They are not only for rooms that predate the table. A room's key state is
     * signed before the room exists and filed as the room is created, so a
     * device that missed that session -- a member invited later, a reinstall --
     * has a room and no state, and the scan is what keeps it usable.
     * `GroupKeyStateManager.propose` is the one caller that does not come
     * through here at all, because it names the ceremony outright: the room it
     * runs in is the NIP-17 room the ceremony was held in, which signs nothing
     * else and has no key state of its own.
     */
    suspend fun completedKey(database: CuratedDatabase, chatRoomId: String): DkgSession? {
        GroupKeyStateManager.keyStateFor(database, chatRoomId)?.let { state ->
            database.dkgSessionDao().getSessionById(state.dkgSessionId)?.takeIf { key ->
                key.stage == DkgRitualStage.COMPLETE &&
                    key.secretShare != null &&
                    key.thresholdPublicKey == state.thresholdPublicKey
            }?.let { return it }
        }

        database.dkgSessionDao().getKeyHoldingSessions().firstOrNull { session ->
            session.stage == DkgRitualStage.COMPLETE &&
                session.thresholdPublicKey?.let {
                    SharedKeyDerivation.marmotGroupId(it) == chatRoomId
                } == true
        }?.let { return it }

        return database.dkgSessionDao()
            .getLatestOwnSessionForChatRoom(chatRoomId)
            ?.takeIf { it.stage == DkgRitualStage.COMPLETE && it.secretShare != null }
    }

    /** Whether this group can sign at all, read by the UI so it offers nothing that would fail. */
    suspend fun canSign(database: CuratedDatabase, chatRoomId: String): Boolean =
        completedKey(database, chatRoomId) != null

    /**
     * The path off [key] that a signature made in this room is derived at, or
     * null when the room is not derived from [key] at all.
     *
     * A room's id *is* its key, so a candidate path is right exactly when
     * walking it reaches the room -- which makes this self-checking rather than
     * trusting. Three candidates, in descending order of how much they know:
     * the room's [GroupKeyState] if the group has signed one, the path recorded
     * in the room's own MIP-01 description, and the default the app derives at.
     * Whichever answers, it answers the same thing: two paths reaching one key
     * is not a thing that happens.
     *
     * Nothing here comes off a proposal. The path decides what key the group
     * signs as, so a proposer able to choose it could have every signer put
     * their share behind an author of the proposer's choosing.
     *
     * ### The room a group signs from before it has one
     *
     * One case cannot be self-checked, because there is nothing yet to check
     * against: the room a ceremony ran in, signing the very statement that lets
     * the room the ceremony's key derives be created -- see
     * `GroupKeyStateManager.propose`. What the group signs as there is the room
     * it is about to make: the ceremony's key at the app's admin path.
     *
     * Two rooms are that room. A group's own ceremony runs in the NIP-17 room its
     * members share, whose id is an aggregation of their keys, so it is derived
     * from nothing and no path reaches it. A *subgroup's* ceremony runs in the
     * parent's Marmot room, which is derived from the parent's key and so reaches
     * itself at some path -- just never from the child's, which is the key being
     * signed under here.
     *
     * Each is admitted on the narrowest thing that identifies it, and the path is
     * the constant rather than anything off the wire. Every input is read from
     * this device's own database, so a proposer still chooses nothing: naming
     * some other ceremony this device holds a share for gets no path at all, and
     * a session with no path signs as the bare threshold key, which is not an
     * identity any room answers to.
     *
     * `key.parentChatRoomId` is the unverified claim off a proposal, and admitting
     * it here grants nothing. The signature it enables is by the child's key over
     * the child's own id -- both derived from a ceremony every signer contributed
     * to -- so a member who put a false parent on a proposal gets a key state for
     * a room made from a key they helped make, which is what they would have got
     * by telling the truth.
     *
     * Null is not a failure. `completedKey` will find a key for a Marmot room
     * that was never derived from it -- the fallback kept for rooms the app no
     * longer makes -- and such a room has no key of its own to sign as, so it
     * signs as the group's threshold key, which is what it always did.
     */
    private suspend fun signingPath(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        key: DkgSession
    ): List<Long>? {
        val thresholdPublicKey = key.thresholdPublicKey ?: return null
        val chatRoomId = localChatRoom.chatRoom.id

        val candidates = listOfNotNull(
            GroupKeyStateManager.keyStateFor(database, chatRoomId)?.pathIndices(),
            SharedKeyDerivation.parsePath(localChatRoom.chatRoom.description),
            SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        )

        candidates.firstOrNull { path ->
            runCatching {
                SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path) == chatRoomId
            }.getOrDefault(false)
        }?.let { return it }

        val isCeremonyRoom = key.chatRoomId == chatRoomId
        val isSubgroupCeremony = key.parentChatRoomId == chatRoomId

        if (isCeremonyRoom && (localChatRoom.chatRoom.mlsGroupState == null || isSubgroupCeremony)) {
            return SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        }

        return null
    }

    /**
     * The unsigned event a session signs: the caller's fields under the room's
     * own key, with the id computed from them.
     *
     * [path] is the room's, so the author is the room's id -- see
     * [SharedKeyDerivation.marmotGroupId], where those are one value. A null
     * path means no derivation at all, which is the group's threshold key
     * itself; that is what a room not derived from the key signs as, because
     * there is no room key for it to be.
     */
    private fun unsignedEventOf(
        key: DkgSession,
        path: List<Long>?,
        kind: Kind,
        tags: Array<Array<String>>,
        content: String,
        createdAt: Long
    ): Event {
        // Nostr identifies an author by the x-only key a BIP-340 signature
        // verifies against, which for a FROST key is the tweaked key the signing
        // cache carries -- not the 33-byte value the ceremony reports.
        val groupPubKey = SharedKeyDerivation
            .derive(key.thresholdPublicKey!!, path ?: emptyList())
            .hex

        return Event(
            id = EventHasher.hashId(
                pubKey = groupPubKey,
                createdAt = createdAt,
                kind = kind,
                tags = tags,
                content = content
            ),
            pubKey = groupPubKey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
            sig = ""
        )
    }

    /**
     * Turns a failed FROST step into an exception so it joins [advance]'s single
     * failure path.
     *
     * The library reports these as `Either`, because a partial signature that
     * will not aggregate is a normal outcome of collecting them from other
     * people rather than a bug. This session has one response to all of them —
     * there is no signature, so it dies and the group is told — and what is
     * worth keeping is which step failed.
     */
    private fun <T> Either<Throwable, T>.orThrow(step: String): T = when (this) {
        is Either.Right -> value
        is Either.Left -> throw IllegalStateException("FROST $step failed: ${value.message}", value)
    }

    /** This device's own message of [kind], if it has published one. */
    private suspend fun ownMessage(
        database: CuratedDatabase,
        session: FrostSigningSession,
        kind: Kind
    ): FrostSignerMessage? = database.frostSigningSessionDao().getMessage(
        sessionId = session.id,
        kind = kind,
        signerPublicKey = session.userPublicKey
    )

    /**
     * Applies [transform] to the *stored* session and returns what was written.
     *
     * Callers hold a session across several steps, each of which may write; going
     * back to the row means a later write cannot silently undo an earlier one by
     * copying from a stale snapshot.
     */
    private suspend fun update(
        database: CuratedDatabase,
        session: FrostSigningSession,
        transform: (FrostSigningSession) -> FrostSigningSession
    ): FrostSigningSession {
        val current = database.frostSigningSessionDao().getSessionById(session.id) ?: session
        val updated = transform(current)

        if (updated == current) return current

        val stamped = updated.copy(updatedAt = Clock.System.now())
        database.frostSigningSessionDao().upsert(stamped)

        return stamped
    }

    /**
     * Moves the progress label forward, never back. Stages are declared in the
     * order the session runs them, so a message arriving out of order cannot
     * walk the UI back down the ladder.
     */
    private suspend fun moveTo(
        database: CuratedDatabase,
        session: FrostSigningSession,
        stage: FrostSigningStage
    ): FrostSigningSession = update(database, session) { current ->
        val settled =
            current.stage == FrostSigningStage.COMPLETE || current.stage == FrostSigningStage.FAILED

        if (settled || current.stage.ordinal >= stage.ordinal) current else current.copy(stage = stage)
    }

    private suspend fun fail(
        database: CuratedDatabase,
        session: FrostSigningSession,
        reason: String,
        culprit: HexKey = session.userPublicKey
    ) {
        // Read before writing so the notice goes out once. A session can be failed
        // from two directions -- a FAILURE message from a member, and a fault
        // raised locally -- and the group does not need to be told twice.
        val current = database.frostSigningSessionDao().getSessionById(session.id) ?: session
        if (current.stage == FrostSigningStage.FAILED) return

        update(database, current) {
            it.copy(stage = FrostSigningStage.FAILED, failureReason = reason)
        }
        announce(
            database = database,
            session = current,
            messageType = ChatMessage.TYPE_FROST_FAILED,
            content = "abandoned the signature. Nothing was signed, and it is safe to ask again.",
            actor = culprit
        )
    }

    /** The stored session, for reading a column back before overwriting it. */
    private suspend fun current(
        database: CuratedDatabase,
        session: FrostSigningSession
    ): FrostSigningSession =
        database.frostSigningSessionDao().getSessionById(session.id) ?: session

    /**
     * Whether this session is waiting on its owner to agree to sign.
     *
     * Mirrors the gate in [advance]: pending exactly when [advance] would stop at
     * it. The two must agree, or the screen offers an approval that does nothing,
     * or none while the session sits still.
     */
    fun isAwaitingApproval(
        session: FrostSigningSession,
        items: List<FrostSigningItem>
    ): Boolean {
        if (session.stage == FrostSigningStage.COMPLETE || session.stage == FrostSigningStage.FAILED) {
            return false
        }

        // The group signed it without needing this member, and [advance] closes
        // the session on its next pass without asking them anything. Offering the
        // decision anyway would be offering two bad answers: a nonce nobody is
        // waiting for, or a refusal that abandons a signature already made.
        if (items.isNotEmpty() && items.all { it.isSigned() }) return false

        return session.signApprovedAt == null
    }

    /**
     * Records that this device's owner agreed to sign, then lets the session run
     * as far as the next thing it is waiting on.
     *
     * Approving a session not waiting on it is a no-op rather than an error: a
     * stale screen left open across a redelivery should not publish anything.
     */
    suspend fun approve(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String
    ) {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return
        val items = database.frostSigningSessionDao().getItems(sessionId)

        if (!isAwaitingApproval(session, items)) {
            logger.i("Session $sessionId is not waiting on an approval; ignoring it")
            return
        }

        update(database, session) { it.copy(signApprovedAt = Clock.System.now()) }

        logger.i("Session $sessionId: signing approved")

        advance(database, localChatRoom, sessionId)
    }

    /**
     * Refuses to sign, and tells the group so the coordinator can pick somebody
     * else rather than wait.
     *
     * A t-of-n group can sign without this member, so declining is a normal
     * outcome and not a failure of the session — but only if it is said out loud.
     * Silence is indistinguishable from a phone in a pocket.
     */
    suspend fun decline(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        sessionId: String
    ) {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return
        val items = database.frostSigningSessionDao().getItems(sessionId)

        if (!isAwaitingApproval(session, items)) return

        fail(
            database = database,
            session = session,
            reason = "Declined on this device",
            culprit = session.userPublicKey
        )
        broadcast(
            database = database,
            localChatRoom = localChatRoom,
            session = session,
            kind = FrostSigningEvents.FAILURE,
            content = "Declined to sign"
        )
    }

    private suspend fun announceStarted(database: CuratedDatabase, session: FrostSigningSession) {
        val count = database.frostSigningSessionDao().countItems(session.id)

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_STARTED,
            content = "asked the group to sign " +
                (if (count > 1) "$count events" else "something") +
                " with its shared key. It takes ${session.threshold} of " +
                "${session.participantCount} members to do it.",
            actor = session.coordinatorPublicKey
        )
    }

    /**
     * Tells the group's chat that this session is waiting on the reader.
     *
     * Written once, guarded by [FrostSigningSession.approvalRequestedAt], because
     * [advance] runs on every arriving message and would otherwise ask again on
     * each one.
     */
    private suspend fun announceApprovalNeeded(
        database: CuratedDatabase,
        session: FrostSigningSession
    ) {
        if (current(database, session).approvalRequestedAt != null) return

        update(database, session) { it.copy(approvalRequestedAt = Clock.System.now()) }

        val count = database.frostSigningSessionDao().countItems(session.id)

        announce(
            database = database,
            session = session,
            messageType = ChatMessage.TYPE_FROST_APPROVAL_NEEDED,
            content = "Your approval is needed to sign " +
                (if (count > 1) "$count events " else "") +
                "with the group's shared key. Nothing has been published from this device yet.",
            actor = session.userPublicKey
        )
    }

    /**
     * Puts one protocol message in the group's chat, as a line naming who sent it.
     *
     * Same reasoning as the ceremony's transcript: these are things a particular
     * member's device did, and a signature made with the group's key is worth
     * being able to watch. The wording describes what a step accomplishes rather
     * than what it is called.
     */
    private suspend fun announceStep(
        database: CuratedDatabase,
        session: FrostSigningSession,
        kind: Kind,
        actor: HexKey
    ) {
        // A batch is still one line per member per step -- what changes is the
        // number in it. Every one of these lines describes work that covered the
        // whole batch, so saying so is the difference between a member reading
        // "signed their part" and knowing what they signed.
        val count = database.frostSigningSessionDao().countItems(session.id)
        val batch = count > 1

        val (messageType, content) = when (kind) {
            FrostSigningEvents.NONCE -> ChatMessage.TYPE_FROST_NONCE to
                "offered to help sign, sending the one-time " +
                    (if (batch) "values their signatures need." else "value their signature needs.")

            FrostSigningEvents.SIGNER_SET -> ChatMessage.TYPE_FROST_SIGNER_SET to
                "chose who is signing and combined their one-time values."

            FrostSigningEvents.PARTIAL_SIGNATURE -> ChatMessage.TYPE_FROST_PARTIAL_SIGNATURE to
                "signed their part" + (if (batch) " of all $count events" else "") +
                    ". On its own it proves nothing; combined with the others it is " +
                    "the group's " + (if (batch) "signatures." else "signature.")

            FrostSigningEvents.SIGNATURE -> ChatMessage.TYPE_FROST_SIGNATURE to
                "combined the parts into the group's " +
                    (if (batch) "$count signatures." else "signature.")

            // PROPOSAL and FAILURE are announced by the code that acts on them --
            // both say more than the message itself carries.
            else -> return
        }

        announce(
            database = database,
            session = session,
            messageType = messageType,
            content = content,
            actor = actor
        )
    }

    /**
     * Puts a signing milestone in the group's chat.
     *
     * Each device writes its own row from the messages it has already received, so
     * this costs no traffic and cannot disagree with the session it describes.
     * Written once by construction rather than de-duplication: every caller checks
     * before it writes, because [ChatMessage] has no key to make a second insert a
     * no-op.
     *
     * Every line names its session. A room signs repeatedly and can have two
     * sessions open at once, so which one a line is about is the difference
     * between a request that is still asking and one that has been answered --
     * see [ChatMessage.frostSigningSessionId].
     */
    private suspend fun announce(
        database: CuratedDatabase,
        session: FrostSigningSession,
        messageType: String,
        content: String,
        actor: HexKey
    ) {
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = actor,
                isUserMessage = actor == session.userPublicKey,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = session.chatRoomId,
                content = content,
                messageType = messageType,
                frostSigningSessionId = session.id
            )
        )
    }

    /**
     * Broadcasts one of this device's own protocol messages AND records it
     * locally. The local copy matters: the coordinator signs too, and its own
     * message has to be in the aggregation alongside everyone else's.
     *
     * Queued before it is recorded, because [advance] republishes any message it
     * has no local copy of. A crash between the two therefore costs a duplicate
     * broadcast — which every receiver folds away — rather than a message the
     * group waits on forever.
     */
    private suspend fun publishOwn(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession,
        kind: Kind,
        content: String
    ) {
        broadcast(database, localChatRoom, session, kind, content)

        val known = ownMessage(database, session, kind) != null

        database.frostSigningSessionDao().upsert(
            FrostSignerMessage(
                sessionId = session.id,
                signerPublicKey = session.userPublicKey,
                kind = kind,
                payload = content
            )
        )

        if (!known) announceStep(database, session, kind, session.userPublicKey)
    }

    /**
     * Queues one of this device's signing messages for the outbound pipeline,
     * on whichever transport the room it is in has.
     *
     * In a Marmot room it is an unprocessed inner event: `NotaryViewModel` picks
     * it up, MLS-encrypts it and broadcasts it as a kind:445 for the room -- the
     * same path every other event in a Marmot group takes.
     *
     * In a NIP-17 room it is a gift-wrap payload, sealed once per recipient and
     * broadcast the way a ritual message is. That is not a second-class path; it
     * is the only one a group has before it owns a Marmot room, which is exactly
     * where the group's first signature is made -- see
     * `GroupKeyStateManager.propose`.
     *
     * The p-tags are the difference between the two, and they are not
     * cosmetic. A group event is encrypted to the group and who is in it is the
     * MLS tree's business, so a Marmot message names nobody; a gift wrap is
     * addressed and sealed per recipient, so a NIP-17 message has to name
     * everybody or the members it left out never see it. Neither shape lets a
     * recipient list decide anything: the signer set comes from the ceremony's
     * host keys either way, so tagging somebody does not put them in it, and
     * failing to tag somebody only stops them hearing.
     */
    private suspend fun broadcast(
        database: CuratedDatabase,
        localChatRoom: LocalChatRoom,
        session: FrostSigningSession,
        kind: Kind,
        content: String,
        includeKey: Boolean = false,
        signerIds: List<Int>? = null
    ) {
        val sessionTags = FrostSigningEvents.assembleTags(
            sessionId = session.id,
            dkgSessionId = if (includeKey) session.dkgSessionId else null,
            signerIds = signerIds
        )

        // No MLS state is what marks a room NIP-17 -- the same reading
        // `NostrNip17Dao.getOrCreateChatRoom` writes and `sendChatMessage` makes
        // when it chooses between a group event and gift wraps.
        val isMarmotRoom = localChatRoom.chatRoom.mlsGroupState != null

        val tags = if (isMarmotRoom) {
            sessionTags
        } else {
            recipientTags(localChatRoom, session.userPublicKey) + sessionTags
        }

        val createdAt = Clock.System.now().epochSeconds

        // The rumor id the outbound pipeline will recompute from these same
        // fields when it assembles the event to encrypt, on either transport.
        val id = EventHasher.hashId(
            pubKey = session.userPublicKey,
            createdAt = createdAt,
            tags = tags,
            content = content,
            kind = kind
        )

        if (isMarmotRoom) {
            database.marmotInnerEventDao().upsert(
                MarmotInnerEvent(
                    id = id,
                    publicKey = session.userPublicKey,
                    kind = kind,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = tags,
                    content = content,
                    chatRoomId = localChatRoom.chatRoom.id
                )
            )
        } else {
            database.giftWrapPayloadDao().upsert(
                GiftWrapPayload(
                    id = id,
                    publicKey = session.userPublicKey,
                    kind = kind,
                    createdAt = Instant.fromEpochSeconds(createdAt),
                    tags = tags,
                    content = content,
                    chatRoomId = localChatRoom.chatRoom.id
                )
            )
        }
    }

    /**
     * One p-tag per member of a NIP-17 room, the sender excepted.
     *
     * The sender is left out rather than tagged because a gift wrap is sealed
     * per recipient and this device already has its own copy -- `publishOwn`
     * records it locally. Deduplicated by member, since `localParticipants` is a
     * row list and a room can carry the same person twice.
     */
    private fun recipientTags(
        localChatRoom: LocalChatRoom,
        senderPublicKey: HexKey
    ): Array<Array<String>> = localChatRoom.localParticipants
        .distinctBy { it.participant.participantPublicKey }
        .filter { it.participant.participantPublicKey != senderPublicKey }
        .map { localParticipant ->
            PTag.assemble(
                localParticipant.participant.participantPublicKey,
                localParticipant.participant.relayHint?.let { NormalizedRelayUrl(it) }
            )
        }
        .toTypedArray()
}
