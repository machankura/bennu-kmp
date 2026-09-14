package to.curare.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.utils.RandomInstance
import com.vitorpamplona.quartz.utils.TimeUtils
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.model.ChatMessage
import to.curare.compose.database.model.GroupSignedEvent
import to.curare.compose.database.model.MarmotInnerEvent
import to.curare.compose.extensions.toHex
import kotlin.time.Clock
import kotlin.time.Instant
import to.curare.compose.nostr.chronicle.ChronicleEvent
import to.curare.compose.nostr.chronicle.ChronicleRequestEvent
import to.curare.compose.nostr.frost.GroupKeyStateEvent
import to.curare.compose.nostr.nip30303.TranslationChunkEvent

/**
 * Building the group's signed record into pages a member who lacks it can apply.
 *
 * See docs/member-chronicle.md. The short of it: a member added after the work was
 * done has none of it and never will, because a group-signed event is applied
 * locally by each device that took part and never goes on the wire. This is how
 * it gets to them.
 *
 * ### The events are the chronicle, and the rows are the fallback
 *
 * `GroupSignedEvent` holds what the group signed, as it signed it, so
 * [signedEventsOf] reads it first: no rebuild, no round-trip risk, and nothing
 * that depends on a row having kept every field of the event it came from. The
 * artifact's version label is the standing example -- it is not on the artifact
 * row at all, and [rebuiltEventsOf] has to go and find it on the initial version
 * or leave the artifact out.
 *
 * A room whose work predates that table has no events on file, so the rebuild
 * stays as the fallback for exactly what the table is missing, keyed by id.
 * Every payload it produces stands or falls on being byte-identical to what was
 * signed; `ChronicleRoundTripTest` is what says it is, per kind, against a real
 * quorum, and it found two faults in the artifact's rebuild the first time it
 * ran -- both of which would have shipped payloads that every receiver drops as
 * forgeries without a word. Both it and everything that exists only to hold it
 * up are marked `@Deprecated`; the fallback can go once no install still holds
 * pre-v13 work, and docs/member-chronicle.md's "Retiring the rebuild" is the list
 * of what goes with it.
 *
 * **The allowlist does real work on the way out now.** The rebuild could only
 * ever produce document kinds; the table holds everything the group has ever
 * signed, `GroupKeyStateEvent` included. So [signedEventsOf] filters on
 * [ChronicleEvent.isChroniclable] before anything else, which is the same rule
 * [applyPage] applies on the way in. Without it `ChronicleEvent.build` would
 * refuse the page and take the whole chronicle down with it.
 *
 * A room's own key state no longer sits in that table under the room -- it is
 * signed in the NIP-17 room the ceremony ran in, before the room exists, and
 * filed under that room; see `GroupKeyStateManager`. That makes the filter a
 * narrower guard than it was and not a redundant one: what it is really stopping
 * is a member replaying any group-signed statement about the record as though it
 * were work, and [applyPage] refuses the same kinds coming the other way. The
 * two are a pair and neither is safe to drop on the strength of the other.
 *
 * ### Nothing unverifiable leaves
 *
 * Every rebuilt event is checked with [GroupKeyStateEvent.isSignedByRoom] before
 * it is packed, against the same room id the recipient will check it with. That
 * is not politeness towards the receiver, who checks anyway. It is what keeps an
 * chronicle honest about its own size: a row that came from a member's rumor
 * cannot be chronicled, and dropping it here rather than letting the recipient
 * drop it means the page count says what will actually arrive.
 */
object ChronicleManager {
    private const val TAG = "ChronicleManager"

    private val logger = Logger.withTag(TAG)

    /**
     * Every group-signed event this device holds for [chatRoomId], paged and
     * addressed to [recipient].
     *
     * Empty when there is nothing to send -- a room with no shared key, a room
     * whose work is all member rumors, or a device that is itself behind. An
     * empty result is not an error and the caller should not report one: the
     * honest answer to "send them the history" in a room with no signed history
     * is nothing.
     */
    suspend fun assemble(
        database: CurareDatabase,
        chatRoomId: String,
        recipient: HexKey,
        chronicleId: String = RandomInstance.bytes(32).toHex(),
        createdAt: Long = TimeUtils.now(),
    ): List<EventTemplate<ChronicleEvent>> {
        val held = signedEventsOf(database, chatRoomId)

        // One rule over both sources: nothing leaves that the recipient could
        // not check for themselves. What a drop means depends on where it came
        // from, and the two are worth telling apart when reading this log --
        // from the rebuild it is the ordinary case of a member's own rumor
        // sitting in the same table as the group's work, and from the record it
        // is a row that has drifted from the event it recorded, which nothing
        // in this app does on purpose.
        val verified = held.filter { GroupKeyStateEvent.isSignedByRoom(it, chatRoomId) }
        if (verified.size != held.size) {
            logger.d(
                "Leaving ${held.size - verified.size} of ${held.size} event(s) out of " +
                    "$chatRoomId's chronicle: nothing verifiably signed by the room"
            )
        }

        val pages = paginate(ChronicleEvent.inApplyOrder(verified))
        if (pages.isEmpty()) {
            logger.i("Nothing signed to chronicle for room $chatRoomId")
            return emptyList()
        }

        logger.i(
            "Chronicling ${verified.size} event(s) for $chatRoomId as $chronicleId, " +
                "${pages.size} page(s) for ${recipient.take(8)}"
        )

        return pages.mapIndexed { index, payloads ->
            ChronicleEvent.build(
                payloads = payloads,
                chronicleId = chronicleId,
                index = index,
                count = pages.size,
                recipient = recipient,
                createdAt = createdAt,
            )
        }
    }

    // ---- Asking, and answering -------------------------------------------

    /**
     * Ask the group for its signed history, if this device holds none of it.
     *
     * Called on entering a room. The condition is deliberately crude -- no
     * dialects and no artifacts -- because the three cases it has to catch look
     * identical from inside the database and should not be told apart:
     *
     *  - a member added after the work was done, whose invite may be recent;
     *  - a reinstall, whose invite is long past;
     *  - a second device, which was never invited at all.
     *
     * Returns whether a request went out.
     *
     * ### Why a device asks rather than being sent one
     *
     * A push from the inviter is an application message in the epoch the add
     * created, and one that overtakes the Welcome is dropped and not deferred --
     * silently, while the inviter sees a success. See docs/marmot-membership.md.
     * Sending a request cannot lose that race, because being able to send it is
     * the proof the race was won: a device that can put an application message
     * into the room has processed its Welcome.
     */
    suspend fun requestIfEmpty(
        database: CurareDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
    ): Boolean {
        val chatRoom = database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom ?: return false
        if (chatRoom.chronicleRequestedAt != null) return false

        val holdsWork = database.mantraDialectDao().getDialectsByChatRoomId(chatRoomId).isNotEmpty() ||
            database.mantraArtifactDao().getArtifactsByChatRoomId(chatRoomId).isNotEmpty()

        if (holdsWork) return false

        queue(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            kind = ChronicleRequestEvent.KIND,
            tags = ChronicleRequestEvent.build().tags,
            content = "",
        )

        database.chatRoomDao().upsert(chatRoom.copy(chronicleRequestedAt = Clock.System.now()))

        announce(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_CHRONICLE_REQUESTED,
            content = "Asked this group for its signed work",
        )

        logger.i("Asked $chatRoomId for its signed history")

        return true
    }

    /**
     * Send [recipient] everything this device can prove about [chatRoomId].
     *
     * Both ways a chronicle goes out are this one call: answering a request, and
     * the push behind a Welcome. Named for what it does rather than for either
     * occasion, because the two differ only in who decided.
     *
     * Any member may send one and none is elected to. A duplicate costs bandwidth
     * and nothing else -- pages are idempotent and every member who is not the
     * recipient ignores them -- so it is waste rather than damage, and the
     * stand-down that would avoid it is an optimisation to add on top rather than
     * a correctness gap to close first.
     *
     * A device with nothing signed sends nothing. Silence is the honest reply
     * from a member who is themselves still catching up, and an empty chronicle
     * would look like an answer.
     */
    suspend fun sendTo(
        database: CurareDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        recipient: HexKey,
    ): Int {
        if (recipient.equals(userPublicKey, ignoreCase = true)) return 0

        val pages = assemble(database, chatRoomId, recipient)
        if (pages.isEmpty()) {
            logger.i("Nothing signed here to send ${recipient.take(8)} for $chatRoomId")
            return 0
        }

        pages.forEach { page ->
            queue(
                database = database,
                chatRoomId = chatRoomId,
                userPublicKey = userPublicKey,
                kind = page.kind,
                tags = page.tags,
                content = page.content,
                createdAt = Instant.fromEpochSeconds(page.createdAt),
            )
        }

        val name = database.profileDao().getProfileByPublicKey(recipient)
            ?.humanReadableNameOrPubkey()
            ?: recipient.take(8)

        announce(
            database = database,
            chatRoomId = chatRoomId,
            userPublicKey = userPublicKey,
            messageType = ChatMessage.TYPE_CHRONICLE_SENT,
            content = "Sent this group's signed work to $name",
        )

        logger.i("Sending ${recipient.take(8)} ${pages.size} chronicle page(s) for $chatRoomId")

        return pages.size
    }

    /**
     * One line in the room's transcript.
     *
     * Written by the device it happened on, for itself. None of these travels --
     * a chronicle is not an event in the group's life, it is one member being
     * caught up -- so a member watching from the side sees nothing, correctly.
     *
     * Content is a whole sentence rather than a predicate, so these stay out of
     * the AUTHORED sets and nothing prefixes a name to them. See
     * [ChatMessage.CHRONICLE_TYPES].
     */
    private suspend fun announce(
        database: CurareDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        messageType: String,
        content: String,
    ) {
        database.chatMessageDao().upsert(
            ChatMessage(
                content = content,
                messageType = messageType,
                chatRoomId = chatRoomId,
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
            )
        )
    }

    /**
     * Put one event on the room's outbound queue.
     *
     * The same shape `FrostSigningManager.broadcast` uses: a `MarmotInnerEvent`
     * with no group event yet, which the notary picks up and encrypts into a
     * kind:445 for the room. The id is the rumor id the outbound pipeline
     * recomputes from these fields when it assembles the event to encrypt.
     *
     * **No `ChatMessage`.** Broadcast does not depend on one -- it is
     * unconditional in `encryptAndSendMarmotInnerEvent`, which is what let the
     * signing sessions travel with no transcript line -- and a chronicle that
     * filed one per page would put a row of envelopes in the room's history. One
     * line per chronicle is right, and it is not writable from here, because the
     * pages of a chronicle are indistinguishable from each other at this point.
     */
    private suspend fun queue(
        database: CurareDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        kind: Int,
        tags: TagArray,
        content: String,
        createdAt: Instant = Clock.System.now(),
    ) {
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = EventHasher.hashId(
                    pubKey = userPublicKey,
                    createdAt = createdAt.epochSeconds,
                    tags = tags,
                    content = content,
                    kind = kind
                ),
                publicKey = userPublicKey,
                kind = kind,
                createdAt = createdAt,
                tags = tags,
                content = content,
                chatRoomId = chatRoomId,
            )
        )
    }

    // ---- Receiving ------------------------------------------------------

    /**
     * What one pass over a room's stored pages did.
     *
     * [skipped] and [failed] are kept apart because only one of them is worth
     * trying again. A payload the allowlist or the signature refused will be
     * refused identically forever; a payload that threw was probably missing a
     * row it depends on, and the next page may bring it.
     */
    data class Outcome(
        val applied: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    ) {
        operator fun plus(other: Outcome) = Outcome(
            applied = applied + other.applied,
            skipped = skipped + other.skipped,
            failed = failed + other.failed,
        )
    }

    /**
     * Take delivery of a chronicle page for [userPublicKey].
     *
     * The page is already on disk by the time this runs -- the inbound path
     * stores every inner event it decrypts before dispatching on kind -- so this
     * does not apply the arriving page as such. It sweeps every page the room
     * has, which covers the new one and any that arrived before the rows they
     * depend on.
     *
     * **A device that is not the named recipient does nothing.** The page is an
     * ordinary group message and it can read it; it has no reason to. It already
     * holds the work, and re-applying would rewrite every one of its rows to
     * point at a chronicle page rather than at the event that introduced it. That
     * is also what keeps the sweep bounded: only the member being caught up ever
     * builds the list.
     */
    suspend fun receive(
        database: CurareDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
        page: Event,
    ): Outcome {
        val recipient = ChronicleEvent(
            id = page.id,
            pubKey = page.pubKey,
            createdAt = page.createdAt,
            tags = page.tags,
            content = page.content,
            sig = page.sig,
        ).recipient()

        if (!recipient.equals(userPublicKey, ignoreCase = true)) {
            logger.d("Chronicle page ${page.id.take(8)} is for ${recipient?.take(8)}; not applying")
            return Outcome()
        }

        return sweep(database, chatRoomId, userPublicKey)
    }

    /**
     * Apply every chronicle page this room holds for [userPublicKey], repeatedly,
     * until a pass stops making progress.
     *
     * Pages arrive over relays in no order, so page 3 can land before page 2 and
     * its chunks have no chapter to hang off yet. Those payloads throw a foreign
     * key violation and would be lost -- unless something runs them again.
     *
     * **No table is needed for that**, because the inbound path already stores
     * every inner event it decrypts. This is the same shape
     * `FrostSigningManager.replayStoredMessages` has, and for the same reason:
     * nothing was actually lost, it just had nowhere to go at the time.
     *
     * **Progress is measured by failures falling, not by rows written.** Every
     * write here is an upsert keyed on the event id, so "applied something" is
     * true on every pass forever and would not terminate. A pass that fails fewer
     * payloads than the last one learned something; a pass that does not is as
     * far as this chronicle gets, and the rest is a hole to be filled by another
     * page or another chronicle.
     *
     * **The answer is the last pass, not the sum of them.** Accumulating would
     * count a payload once per pass it survived and report failures the next pass
     * went on to fix, so `failed > 0` would stop meaning "still missing". The
     * final pass is the settled state: what these pages can apply, what they will
     * never apply, and what is still waiting on something that has not arrived.
     */
    suspend fun sweep(
        database: CurareDatabase,
        chatRoomId: String,
        userPublicKey: HexKey,
    ): Outcome {
        val pages = database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId, listOf(ChronicleEvent.KIND))
            .filter { addressedTo(it, userPublicKey) }

        if (pages.isEmpty()) return Outcome()

        var pass = Outcome()
        var previousFailures = Int.MAX_VALUE

        while (true) {
            pass = Outcome()
            pages.forEach { pass += applyPage(database, chatRoomId, it) }

            if (pass.failed == 0 || pass.failed >= previousFailures) {
                if (pass.failed > 0) {
                    logger.w(
                        "Stopped sweeping $chatRoomId with ${pass.failed} payload(s) still " +
                            "unapplied: nothing in the pages it holds satisfies them"
                    )
                }
                break
            }

            previousFailures = pass.failed
        }

        logger.i(
            "Swept ${pages.size} chronicle page(s) for $chatRoomId: " +
                "${pass.applied} applied, ${pass.skipped} skipped, ${pass.failed} left"
        )

        // An answer arrived, so the room may ask again if it turns out to be a
        // partial one. Cleared on anything applied rather than on the chronicle
        // reporting itself complete: a page count is the sender's claim about the
        // transfer, not about the group's record, and a member who left work out
        // would otherwise have the last word.
        if (pass.applied > 0) {
            database.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.let { chatRoom ->
                if (chatRoom.chronicleRequestedAt != null) {
                    database.chatRoomDao().upsert(chatRoom.copy(chronicleRequestedAt = null))

                    // One line per answered request, which is as close to one per
                    // chronicle as this can get: the pages of a chronicle are not
                    // distinguishable from each other here, and clearing the stamp
                    // is exactly the moment a catch-up stops being pending.
                    //
                    // A push behind a Welcome writes none, because the room was
                    // never asked. That is right: it lands before the member has
                    // opened the room, and "caught up on work you have not seen
                    // yet" is a line about nothing.
                    announce(
                        database = database,
                        chatRoomId = chatRoomId,
                        userPublicKey = userPublicKey,
                        messageType = ChatMessage.TYPE_CHRONICLE_RECEIVED,
                        content = "Caught up on ${pass.applied} item(s) of this group's signed work",
                    )
                }
            }
        }

        return pass
    }

    private fun addressedTo(stored: MarmotInnerEvent, userPublicKey: HexKey): Boolean =
        ChronicleEvent(
            id = stored.id,
            pubKey = stored.publicKey,
            createdAt = stored.createdAt.epochSeconds,
            tags = stored.tags,
            content = stored.content,
            sig = "",
        ).recipient().equals(userPublicKey, ignoreCase = true)

    /**
     * One page, checked payload by payload and applied in dependency order.
     *
     * **Per payload, not per page.** A forged payload sitting beside honest ones
     * costs itself and nothing else -- the same rule `MarmotInboundManager` uses
     * for a forged direct message, and for the same reason: this runs inside the
     * inbound transaction, and one bad event must not take the room down with it.
     * Refusing the whole page would also let a single forgery deny an entire
     * chronicle.
     *
     * The page's own framing is still all-or-nothing; see
     * [ChronicleEvent.decodePage] for why those two are not in tension.
     */
    private suspend fun applyPage(
        database: CurareDatabase,
        chatRoomId: String,
        stored: MarmotInnerEvent,
    ): Outcome {
        val payloads = ChronicleEvent.decodePage(stored.content)
        if (payloads == null) {
            logger.w("Chronicle page ${stored.id.take(8)} does not read as a page; dropping it")
            return Outcome()
        }

        // Read once for the page rather than per payload: every event in an
        // chronicle was signed by the room it is arriving in, so they all share
        // its path. Null when the recipient has no key state yet -- which is
        // the normal case for the member a chronicle exists for, and why
        // `GroupSignedEvent.derivationPath` is nullable and `record` fills it
        // in later rather than overwriting it with null.
        val derivationPath = GroupKeyStateManager.keyStateFor(database, chatRoomId)?.derivationPath

        var outcome = Outcome()

        ChronicleEvent.inApplyOrder(payloads).forEach { payload ->
            // The allowlist first, and it is not a formality. Verification admits
            // an event to the apply path on the strength of the group's
            // signature, which makes every kind the group has ever signed
            // replayable by any member at any time -- a GroupKeyStateEvent from
            // an earlier epoch passes the signature check perfectly.
            if (!ChronicleEvent.isChroniclable(payload.kind)) {
                logger.w(
                    "Chronicle page ${stored.id.take(8)} carries kind ${payload.kind}, " +
                        "which a chronicle may not deliver; dropping ${payload.id.take(8)}"
                )
                outcome += Outcome(skipped = 1)
                return@forEach
            }

            if (!GroupKeyStateEvent.isSignedByRoom(payload, chatRoomId)) {
                logger.w(
                    "Chronicle page ${stored.id.take(8)} carries ${payload.id.take(8)}, " +
                        "which room $chatRoomId did not sign; dropping it"
                )
                outcome += Outcome(skipped = 1)
                return@forEach
            }

            outcome += try {
                // Kept as the group signed it, before it is turned into rows.
                // The check above is what earns it that: a payload reaching here
                // is one the room signed, which is the same standard
                // `FrostSigningManager` records its own batches on. Without this
                // a member who was handed their history would hold the rows and
                // none of the events, and could never build a chronicle of their
                // own to hand on.
                database.groupSignedEventDao().record(
                    GroupSignedEvent.fromEvent(
                        event = payload,
                        chatRoomId = chatRoomId,
                        derivationPath = derivationPath,
                    )
                )

                // The chat line this returns is deliberately dropped rather than
                // filed. ChatMessage has an autoGenerate primary key, so there is
                // no id to dedupe on and every applied payload would mint a new
                // row -- giving the recipient a synthetic transcript dated now,
                // and another one on every pass of the sweep. The chronicle
                // restores the work; the conversation is forward secret and stays
                // gone.
                ChatMessage.applyInnerEvent(
                    database = database,
                    groupId = chatRoomId,
                    event = payload,
                    marmotGroupEventId = stored.marmotGroupEventId,
                    marmotInnerEventId = stored.id,
                    senderPublicKey = payload.pubKey,
                    isUserMessage = false,
                    createdAt = stored.createdAt,
                    groupSignedEventId = payload.id,
                )
                Outcome(applied = 1)
            } catch (error: Throwable) {
                // Almost always a foreign key: this payload names a row that is
                // in a page which has not arrived yet. Retryable, which is what
                // the sweep is for, so it is counted rather than logged loudly.
                logger.d(
                    "Could not apply ${payload.id.take(8)} from chronicle page " +
                        "${stored.id.take(8)} yet: ${error.message}"
                )
                Outcome(failed = 1)
            }
        }

        return outcome
    }

    /**
     * Every chroniclable event this device holds for the room: the ones the group
     * signed here or sent here, plus anything only the rows still remember.
     *
     * The record comes first because it is the event rather than a reconstruction
     * of one, and the rebuild fills the gap behind it -- keyed by id, so an event
     * held both ways travels once. A room that upgraded mid-life has both, and
     * neither half is complete on its own.
     *
     * The two disagree only in one direction worth naming. An id the rebuild
     * produces that the record does not hold is either work from before the
     * table existed, which is the point of the fallback, or a rebuild that has
     * gone wrong -- and a wrong rebuild hashes to an id whose signature does not
     * verify, so the filter in [assemble] drops it either way rather than
     * shipping a payload every receiver reads as a forgery.
     *
     * Order does not matter at this point; [ChronicleEvent.inApplyOrder] settles it
     * afterwards. It is stable within a rank, so a room holding some of its work
     * both ways can order two chapters differently from a member holding one way
     * only. That costs nothing: pages are idempotent and applied payload by
     * payload, and two members already differed by the order their rows were
     * written in.
     */
    private suspend fun signedEventsOf(
        database: CurareDatabase,
        chatRoomId: String,
    ): List<Event> {
        val recorded = database.groupSignedEventDao()
            .getByChatRoomId(chatRoomId)
            // The allowlist, applied to the source that can actually trip it --
            // see the class comment. A key state on file is the room's own, and
            // sending it would be handing every member a validly signed
            // statement about what the room signs with, replayable forever.
            .filter { ChronicleEvent.isChroniclable(it.kind) }
            .map { it.toEvent() }

        val onFile = recorded.mapTo(mutableSetOf()) { it.id }
        val rebuilt = rebuiltEventsOf(database, chatRoomId).filterNot { it.id in onFile }

        if (rebuilt.isNotEmpty()) {
            logger.d(
                "Chronicling $chatRoomId: ${recorded.size} event(s) as the group signed them, " +
                    "${rebuilt.size} rebuilt from rows that predate the record"
            )
        }

        return currentTranslationsOnly(recorded + rebuilt)
    }

    /**
     * Drops every translation of a passage but the one that stands.
     *
     * The record keeps every event the group ever signed, deliberately -- a
     * signature is the group's statement and discarding one is not this table's
     * business. The rows do not: `ChatMessage.applyInnerEvent` supersedes a
     * translation chunk, deleting the one it replaces, so a passage translated
     * three times leaves one row and three events.
     *
     * That difference reaches the chronicle the moment it is read from the record
     * rather than rebuilt from rows, and it compounds: every draft a group ever
     * signed would travel in every chronicle it ever sends, for as long as the room
     * exists. A chronicle exists to catch a member up on where the group has got
     * to, not to hand them its drafting history.
     *
     * **The rule is the applying arm's, restated rather than approximated**:
     * newest by the timestamp the group signed at, id breaking a tie. It has to
     * be, or the chronicle would ship one translation as current and the recipient
     * would settle on another -- and since both are validly signed, nothing
     * downstream would notice the disagreement.
     *
     * Dropping the drafts is safe precisely because the recipient applies the
     * same rule: it is not what keeps them correct, only what stops them being
     * sent work they would immediately discard.
     *
     * A translation naming no passage is left alone rather than grouped with
     * others: it is unappliable either way, and letting one stand in for a whole
     * passage would let a malformed event suppress a good one.
     */
    private fun currentTranslationsOnly(events: List<Event>): List<Event> {
        val translations = events.filter { it.kind == TranslationChunkEvent.KIND }
        if (translations.size < 2) return events

        val current = translations
            .groupBy { event ->
                val translation = TranslationChunkEvent(
                    id = event.id,
                    pubKey = event.pubKey,
                    createdAt = event.createdAt,
                    tags = event.tags,
                    content = event.content,
                    sig = event.sig,
                )

                val chapterId = translation.translationChapterId()
                val chunkId = translation.chunkId()

                if (chapterId == null || chunkId == null) event.id else "$chapterId/$chunkId"
            }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))
            }
            .mapTo(mutableSetOf()) { it.id }

        if (current.size != translations.size) {
            logger.d(
                "Leaving ${translations.size - current.size} superseded translation(s) " +
                    "out of the chronicle"
            )
        }

        return events.filter { it.kind != TranslationChunkEvent.KIND || it.id in current }
    }

    /**
     * The room's rows, rebuilt into the events they came from.
     *
     * Walked down the tree rather than queried per kind, because only dialects
     * and artifacts have a by-room query and the rest hang off a parent. The walk
     * is also what makes an artifact's version label reachable: it is not on the
     * artifact row -- see `MantraArtifact.toArtifactEvent` -- and the version it
     * went into is one step away here.
     *
     * Still walked on every chronicle, and by now it contributes nothing in most
     * rooms: everything signed or applied since `GroupSignedEvent` existed is on
     * file as an event, and [signedEventsOf] discards whatever this rebuilds of
     * it. The walk is a handful of indexed queries against a room's own rows,
     * and it is what makes a half-upgraded room whole, so it runs rather than
     * being skipped when the record looks complete -- there is no way to tell a
     * complete record from a partial one without doing it.
     *
     * It returns everything it can rebuild and lets [signedEventsOf] and the
     * verify filter decide what can travel.
     *
     * Deprecated rather than merely legacy: it is a whole mechanism kept alive
     * for a shrinking set of rows, and it takes eight `toXEvent()` methods and a
     * ten-case round-trip suite with it. **docs/member-chronicle.md, "Retiring the
     * rebuild", is the checklist** -- what goes, what only looks like it goes,
     * and the one thing that has to be true before any of it can.
     */
    @Deprecated(
        "Chronicle fallback for work signed before the GroupSignedEvent table. " +
            "Goes when the last pre-v13 install does -- see the removal checklist " +
            "in docs/member-chronicle.md."
    )
    private suspend fun rebuiltEventsOf(
        database: CurareDatabase,
        chatRoomId: String,
    ): List<Event> = buildList {
        database.mantraDialectDao().getDialectsByChatRoomId(chatRoomId).forEach {
            add(it.toDialectEvent())
        }

        database.mantraArtifactDao().getArtifactsByChatRoomId(chatRoomId).forEach { artifact ->
            val versions = database.mantraArtifactVersionDao()
                .getArtifactVersionsByArtifactId(artifact.id)

            // The version an artifact starts life with carries the label the
            // artifact was signed with, and `ArtifactVersionEvent.initialVersionOf`
            // gives it the artifact's own timestamp. The label is still not on the
            // artifact row -- `fromArtifactEvent` drops it -- so this is where it
            // comes back from. An artifact with no such version is one this device
            // cannot rebuild, which is a gap in the chronicle rather than a reason to
            // abandon it.
            val versionLabel = versions
                .firstOrNull { it.createdAt == artifact.createdAt }
                ?.versionLabel

            if (versionLabel == null) {
                logger.w("Artifact ${artifact.id} has no initial version; leaving it out")
            } else {
                add(artifact.toArtifactEvent(versionLabel = versionLabel))
            }

            versions.forEach { version ->
                add(version.toArtifactVersionEvent())

                database.mantraChapterDao()
                    .getChaptersByArtifactVersionId(version.id)
                    .forEach { chapter ->
                        add(chapter.toChapterEvent())

                        database.mantraChunkDao()
                            .getChunksByChapterId(chapter.id)
                            .forEach { add(it.toChunkEvent()) }
                    }

                database.mantraTranslationArtifactVersionDao()
                    .getTranslationsByArtifactVersionId(version.id)
                    .forEach { translationVersion ->
                        add(translationVersion.toTranslationArtifactVersionEvent())

                        database.mantraTranslationChapterDao()
                            .getTranslationChaptersByTranslationArtifactVersionId(translationVersion.id)
                            .forEach { translationChapter ->
                                add(translationChapter.toTranslationChapterEvent())

                                // Only the translation that survived supersession
                                // is here to be found: retranslating a passage
                                // changes the text and so the event id, and the
                                // arm that applies one drops what it replaces. So
                                // a chronicle carries a group's current answer to
                                // each passage rather than its drafts, which is
                                // the same thing every other member holds.
                                database.mantraTranslationChunkDao()
                                    .getTranslationChunksByTranslationChapterId(translationChapter.id)
                                    .forEach { add(it.toTranslationChunkEvent()) }
                            }
                    }
            }
        }
    }

    /**
     * [events] cut into pages that fit, keeping the order they arrive in.
     *
     * Greedy: fill a page until the next event would cross either cap. Both are
     * checked because they bind different chronicles -- a room of one-line dialects
     * hits the count first and a room of chapters hits the bytes.
     *
     * The bytes are the page's *escaped* bytes -- what it will occupy as the
     * `content` of the Marmot app event that carries it, which is what a
     * conformant decoder bounds. Dense JSON payloads grow by half on the way in,
     * and a page measured raw was over that bound while under this one. See
     * [ChronicleEvent.escapedByteSize].
     *
     * An event too large to share a page with anything is given one of its own.
     * One too large for even that is dropped with a log rather than failing the
     * chronicle: a chapter nobody can chronicle is a hole, and a member who gets
     * nothing at all is a bigger one.
     */
    private fun paginate(events: List<Event>): List<List<Event>> {
        val pages = mutableListOf<List<Event>>()
        var page = mutableListOf<Event>()
        // The brackets an empty page already costs.
        var bytes = 2

        events.forEach { event ->
            // Plus the comma this event needs if it is not first on its page.
            val size = ChronicleEvent.escapedByteSize(event.toJson()) + 1

            if (2 + size > ChronicleEvent.MAX_PAGE_BYTES) {
                logger.w(
                    "Event ${event.id} is $size bytes and will not fit a " +
                        "${ChronicleEvent.MAX_PAGE_BYTES}-byte page; leaving it out of the chronicle"
                )
                return@forEach
            }

            val full = page.isNotEmpty() &&
                (bytes + size > ChronicleEvent.MAX_PAGE_BYTES || page.size >= ChronicleEvent.MAX_PAGE_EVENTS)

            if (full) {
                pages.add(page)
                page = mutableListOf()
                bytes = 2
            }

            page.add(event)
            bytes += size
        }

        if (page.isNotEmpty()) pages.add(page)

        return pages
    }
}
