package to.curare.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vitorpamplona.quartz.experimental.nip82SoftwareApps.application.name
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.model.traits.LocalStoreEntity
import to.curare.compose.database.model.traits.SoftDeletableEntity
import to.curare.compose.database.model.traits.TimestampedEntity
import to.curare.compose.database.model.traits.UserViewableEntity
import to.curare.compose.exceptions.MarmotUnprocessableInnerEventException
import to.curare.compose.managers.ChronicleManager
import to.curare.compose.managers.GroupKeyStateManager
import to.curare.compose.managers.SubgroupManager
import to.curare.compose.extensions.toHex
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import to.curare.compose.nostr.GroupCuratedSchema
import to.curare.compose.nostr.GroupNostrProfile
import to.curare.compose.nostr.curated.CuratedEntryEvent
import to.curare.compose.nostr.curated.CuratedSchemaEvent
import to.curare.compose.nostr.GroupRelaySet
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import to.curare.compose.nostr.dkg.DkgRitualEvents
import to.curare.compose.nostr.frost.FrostSigningEvents
import to.curare.compose.nostr.frost.GroupKeyStateEvent
import to.curare.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import to.curare.compose.nostr.nip30303.ArtifactEvent
import to.curare.compose.nostr.nip30303.ArtifactVersionEvent
import to.curare.compose.nostr.nip30303.ChapterEvent
import to.curare.compose.nostr.nip30303.ChunkEvent
import to.curare.compose.nostr.nip30303.DialectEvent
import to.curare.compose.nostr.chronicle.ChronicleEvent
import to.curare.compose.nostr.chronicle.ChronicleRequestEvent
import to.curare.compose.nostr.nip30303.SubmissionEvent
import to.curare.compose.nostr.nip30303.TranslationArtifactVersionContributorListEvent
import to.curare.compose.nostr.nip30303.TranslationArtifactVersionEvent
import to.curare.compose.nostr.nip30303.TranslationChapterEvent
import to.curare.compose.nostr.nip30303.TranslationChunkEvent
import to.curare.compose.nostr.nip30303.TranslationContributorListEvent
import to.curare.compose.nostr.nip30303.TranslationEvent
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.firstTagValue
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import to.curare.compose.nostr.MarmotDirectMessage
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["id"],
            childColumns = ["chatRoomId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GiftWrapPayload::class,
            parentColumns = ["id"],
            childColumns = ["giftWrapPayloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MarmotGroupEvent::class,
            parentColumns = ["id"],
            childColumns = ["marmotGroupEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MarmotInnerEvent::class,
            parentColumns = ["id"],
            childColumns = ["marmotInnerEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // The chat list reads the newest line of every room the user is in, once per
    // room, and re-reads all of them each time a message lands anywhere. Without
    // this that is a scan of every message this device holds, per room, per
    // arrival -- work that grows with the whole history rather than with the room.
    indices = [Index("chatRoomId")],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val senderPublicKey: String,
    val isUserMessage: Boolean,

    val giftWrapPayloadId: HexKey?,
    val marmotGroupEventId: HexKey?,
    val marmotInnerEventId: HexKey?,
    val chatRoomId: String,

    val replyToMessageId: Long? = null,
    val quotedMessageId: Long? = null,

    val content: String,
    val messageType: String = "message",

    /**
     * Who a direct message was for, or null for anything else.
     *
     * Carried on the row rather than read back off the wrap so the transcript can name
     * the recipient without a join, on both the line the two of them can read and the
     * line everybody else gets. See docs/marmot-direct-messages.md.
     */
    val directMessageRecipientPublicKey: HexKey? = null,

    /**
     * The signing session a [FROST_TYPES] line belongs to, or null for anything else.
     *
     * The transcript used to work this out from the type and the clock: a request
     * was over once a line of the right type appeared after it. That reads a room
     * signing one thing at a time correctly and a room signing two things at once
     * wrongly -- a chapter and the translation that depends on it are proposed as
     * two sessions, and either one finishing dropped the summons on both. There is
     * nothing in a row's type or time that can tell them apart, so the session has
     * to be on the row.
     *
     * Null on every line written before this column existed, which the rules below
     * fall back to their old behaviour for. Correct for those rooms too: nothing
     * that predates the batch proposals ran two sessions at once.
     */
    val frostSigningSessionId: String? = null,

    /**
     * The ceremony a [DKG_TYPES] line belongs to, or null for anything else.
     *
     * The pair to [frostSigningSessionId], and added for the same reason one
     * ceremony at a time stopped being true. A room's ceremony room is derived
     * from its members, so a subgroup whose admins are the whole group runs in
     * the room the group's own ceremony ran in -- and then "the room's ceremony"
     * names two things. A screen opened from one of these lines has to reach the
     * ceremony the line is *about*, not the newest one in the room, or a member
     * asked to join a subgroup is shown a ceremony that finished last week and
     * told there is nothing to do.
     *
     * Null on every line written before this column existed, which the readers
     * below fall back to the clock for. Correct for those rooms too: nothing that
     * predates subgroups ran two ceremonies at once.
     */
    val dkgSessionId: String? = null,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = createdAt,
    override val savedAt: Instant = Clock.System.now(),
    override val viewedAt: Instant? = null,
    override val deletedAt: Instant? = null, // Who this message should be seen by (used to derive conversationId)
): TimestampedEntity, LocalStoreEntity, UserViewableEntity, SoftDeletableEntity {

    companion object {
        private val logger = Logger.withTag("ChatMessage")

        /**
         * A one-to-one message inside a group, as a line in the group's chat.
         *
         * Written by both sides of the room. For its two parties [content] is the words;
         * for everybody else it is empty, and the line says only that a private message
         * was sent and to whom -- which is all the group can learn, and all it should.
         *
         * [directMessageRecipientPublicKey] is set on every one of these regardless of
         * who can read it, because naming the recipient is the point of the line the
         * bystanders get. See docs/marmot-direct-messages.md.
         */
        const val TYPE_DIRECT_MESSAGE = "directMessage"

        /**
         * A ChillDKG ritual message, as a line in the group's chat.
         *
         * There is one per protocol message the ritual sends -- every member's host
         * key, both of their rounds, and both of the coordinator's aggregates --
         * plus the three that bracket them: started, complete, abandoned.
         *
         * These rows are not anybody's words: no [ChatMessage.giftWrapPayloadId], no
         * event behind them, and nothing sent to say them. Each device writes its own
         * from the ritual messages it already received, which is why they cost no
         * traffic and cannot disagree with the ritual they describe.
         *
         * [ChatMessageListViewModel] renders these as a system line rather than a
         * bubble, since attributing "a shared key ceremony started" to the
         * coordinator would read as something they said.
         */
        const val TYPE_DKG_STARTED = "dkgStarted"
        const val TYPE_DKG_HOST_KEY = "dkgHostKey"
        const val TYPE_DKG_ROUND_1 = "dkgRound1"
        const val TYPE_DKG_COORDINATOR_ROUND_1 = "dkgCoordinatorRound1"
        const val TYPE_DKG_ROUND_2 = "dkgRound2"
        const val TYPE_DKG_CERTIFICATE = "dkgCertificate"
        const val TYPE_DKG_COMPLETE = "dkgComplete"
        const val TYPE_DKG_FAILED = "dkgFailed"

        /**
         * The ritual is waiting on this device's owner and will publish nothing
         * until they act. Unauthored on purpose: these are addressed to the reader
         * rather than said by anyone, so they read "Your approval is needed", not
         * "You your approval is needed".
         *
         * One type per step rather than one for all three, because the row is the
         * only thing the transcript keeps: a line rendered days later has no session
         * to ask what was being requested, so which step it was has to be in the
         * type. Sharing one type left every request wearing the same icon.
         */
        const val TYPE_DKG_APPROVAL_NEEDED_HOST_KEY = "dkgApprovalNeededHostKey"
        const val TYPE_DKG_APPROVAL_NEEDED_ROUND_1 = "dkgApprovalNeededRound1"
        const val TYPE_DKG_APPROVAL_NEEDED_ROUND_2 = "dkgApprovalNeededRound2"

        /**
         * The single type the three above replaced. Still recognised so rows already
         * written keep rendering as system lines instead of turning into chat
         * bubbles attributed to the reader. Nothing writes it any more.
         */
        const val TYPE_DKG_APPROVAL_NEEDED_LEGACY = "dkgApprovalNeeded"

        /**
         * The line each request is answered by: the step it asked for, published by
         * this device. Approving is the only thing that causes that to be written,
         * so its presence is what marks a request done.
         *
         * The legacy type is absent on purpose -- it does not say which step it was
         * asking for, so nothing can be said about whether it was answered.
         */
        val DKG_REQUEST_FULFILMENTS = mapOf(
            TYPE_DKG_APPROVAL_NEEDED_HOST_KEY to TYPE_DKG_HOST_KEY,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_1 to TYPE_DKG_ROUND_1,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_2 to TYPE_DKG_ROUND_2,
        )

        /** The lines that are asking rather than reporting. */
        val DKG_REQUEST_TYPES = setOf(
            TYPE_DKG_APPROVAL_NEEDED_HOST_KEY,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_1,
            TYPE_DKG_APPROVAL_NEEDED_ROUND_2,
            TYPE_DKG_APPROVAL_NEEDED_LEGACY,
        )

        val DKG_TYPES = setOf(
            TYPE_DKG_STARTED,
            TYPE_DKG_HOST_KEY,
            TYPE_DKG_ROUND_1,
            TYPE_DKG_COORDINATOR_ROUND_1,
            TYPE_DKG_ROUND_2,
            TYPE_DKG_CERTIFICATE,
            TYPE_DKG_COMPLETE,
            TYPE_DKG_FAILED,
        ) + DKG_REQUEST_TYPES

        /**
         * The ritual lines somebody did, as opposed to ones that simply happened.
         * For these [senderPublicKey] is the member who acted, and their content is
         * written as a predicate for their name to be read in front of.
         *
         * Every protocol message is one of these: each is a thing a named member's
         * device sent, and which member it was is the whole point of showing it --
         * a ritual finishes only once every member has taken part, so a stalled one
         * is a question about who has not.
         *
         * A finished ceremony is the exception, and has no actor: the group ends up
         * with a key, nobody hands it to them.
         */
        /**
         * A FROST signing session, as lines in the group's chat.
         *
         * The same shape as the ceremony's, and for the same reason: signing with
         * the group's key is otherwise a black box, and a session stalled on one
         * member gives no way to see whose door to knock on.
         */
        const val TYPE_FROST_STARTED = "frostStarted"
        const val TYPE_FROST_NONCE = "frostNonce"
        const val TYPE_FROST_SIGNER_SET = "frostSignerSet"
        const val TYPE_FROST_PARTIAL_SIGNATURE = "frostPartialSignature"
        const val TYPE_FROST_SIGNATURE = "frostSignature"
        const val TYPE_FROST_COMPLETE = "frostComplete"
        const val TYPE_FROST_FAILED = "frostFailed"

        /**
         * A member refused to sign, and the session went on without them.
         *
         * Its own type rather than a failure, because it is not one: a t-of-n key
         * can do without `n - t` members, and this line is one of the refusals
         * the key can absorb. The refusal that leaves too few to sign is written
         * as [TYPE_FROST_FAILED] instead, saying both that the member declined
         * and that it was the one that ended the session.
         */
        const val TYPE_FROST_DECLINED = "frostDeclined"

        /**
         * The coordinator re-published a session for the members it had heard
         * nothing from.
         *
         * Its own type rather than a step, because nothing moved: somebody
         * knocked. A session can be left waiting on a member whose copy of the
         * proposal never arrived -- MLS dropped the generation, or a
         * disappearing-message group expired it off the relay -- and a resend is
         * the only thing that reaches them. The line exists so a second knock
         * reads as a second knock. See docs/frost-session-resend.md.
         */
        const val TYPE_FROST_RESENT = "frostResent"

        /** Addressed to the reader rather than said by anyone -- see [DKG_REQUEST_TYPES]. */
        const val TYPE_FROST_APPROVAL_NEEDED = "frostApprovalNeeded"

        /**
         * Answering the request publishes this device's nonce, not its signature:
         * approving is agreeing to take part, and the coordinator may then pick a
         * quorum that does not include this member. Keying the answer on the
         * partial signature would leave a member who agreed, and was not needed,
         * looking like they never replied.
         */
        val FROST_REQUEST_FULFILMENTS = mapOf(
            TYPE_FROST_APPROVAL_NEEDED to TYPE_FROST_NONCE,
        )

        /** The signing lines that ask rather than report. */
        val FROST_REQUEST_TYPES = setOf(TYPE_FROST_APPROVAL_NEEDED)

        /**
         * The signing lines that end a session, whichever way it went.
         *
         * A request usually stops being one by being answered, but that is not
         * the only way. A quorum that signs without the reader asks them for
         * nothing further, and a session that is abandoned asks nobody for
         * anything -- in both there is nothing of the reader's own either side
         * of the request, so the session's own ending is the only thing left to
         * read it from.
         *
         * A ceremony has no equivalent because a ceremony step can only be
         * taken or waited for. Signing is the one thing a member can refuse --
         * and a refusal the session survives is the third way a request can be
         * over, read by [settledRequests] from the reader's own
         * [TYPE_FROST_DECLINED] line rather than from here: it settles only
         * the refuser's request, where these settle everybody's.
         */
        val FROST_SETTLEMENTS = setOf(TYPE_FROST_COMPLETE, TYPE_FROST_FAILED)

        /** Every signing line, for rendering them as system lines rather than bubbles. */
        val FROST_TYPES = setOf(
            TYPE_FROST_STARTED,
            TYPE_FROST_NONCE,
            TYPE_FROST_SIGNER_SET,
            TYPE_FROST_PARTIAL_SIGNATURE,
            TYPE_FROST_SIGNATURE,
            TYPE_FROST_COMPLETE,
            TYPE_FROST_FAILED,
            TYPE_FROST_DECLINED,
            TYPE_FROST_RESENT,
            TYPE_FROST_APPROVAL_NEEDED,
        )

        /**
         * The signing lines somebody did, as opposed to ones that simply happened.
         * Their content is written as a predicate for the actor's name to be read
         * in front of. A finished signature has no actor: the group ends up with
         * one, nobody hands it to them.
         */
        val FROST_AUTHORED_TYPES = setOf(
            TYPE_FROST_STARTED,
            TYPE_FROST_NONCE,
            TYPE_FROST_SIGNER_SET,
            TYPE_FROST_PARTIAL_SIGNATURE,
            TYPE_FROST_SIGNATURE,
            TYPE_FROST_FAILED,
            TYPE_FROST_DECLINED,
            TYPE_FROST_RESENT,
        )

        val DKG_AUTHORED_TYPES = setOf(
            TYPE_DKG_STARTED,
            TYPE_DKG_HOST_KEY,
            TYPE_DKG_ROUND_1,
            TYPE_DKG_COORDINATOR_ROUND_1,
            TYPE_DKG_ROUND_2,
            TYPE_DKG_CERTIFICATE,
            TYPE_DKG_FAILED,
        )

        /**
         * A kind:445 arrived that the group could not read at the time.
         *
         * Both are placeholders for a message still to come: the outer layer was
         * encrypted under an epoch whose exporter secret we did not hold yet, or
         * the commit is one of several competing for an epoch and none has been
         * applied. Neither says anything a member wrote.
         */
        /**
         * Handing a member the group's signed record, as lines in the chat.
         *
         * One line per chronicle rather than per page. A member who joins a working
         * group has a room that fills itself in without explanation, and the
         * alternative to saying so once is either silence -- which looks like a
         * group that has done nothing -- or a line per applied payload, which is
         * the transcript the chronicle deliberately does not write.
         *
         * Content is self-contained: these are not predicates for a name to be
         * read in front of, so they stay out of the AUTHORED sets. A recipient's
         * name is written into the sent line the way the invite line writes one,
         * which does not follow a rename and is the accepted cost for a line
         * about a thing that happened once.
         *
         * The received line names no sender on purpose. A chronicle can be
         * assembled out of pages from more than one member, so attributing the
         * catch-up to one of them would be a guess dressed as a fact.
         */
        const val TYPE_CHRONICLE_REQUESTED = "chronicleRequested"
        const val TYPE_CHRONICLE_SENT = "chronicleSent"
        const val TYPE_CHRONICLE_RECEIVED = "chronicleReceived"

        /**
         * The group certified another group as its child.
         *
         * A system line rather than a bubble: nobody said it, the group signed
         * it. It is written in the *parent's* room, which is the only place the
         * certificate is ever made, and it is there for the members of that room
         * who are not in the child -- for whom this is the only notice a subgroup
         * exists at all.
         */
        const val TYPE_SUBGROUP_CERTIFIED = "subgroupCertified"

        /**
         * The group signed what it says about itself on nostr.
         *
         * A system line rather than a bubble, for the reason every group-signed
         * line is one: nobody said it, the group signed it. Whoever filled the
         * form in proposed it and a quorum agreed, and the transcript of that
         * happening is the signing session's own -- this is the line saying what
         * the group now is, which is worth having in the room because a rename is
         * the kind of thing members should not have to go looking for.
         *
         * Written only for a profile the *room* signed. A kind:0 authored by
         * anybody else that reaches this room is a member's own profile
         * travelling as a rumor, which is not the group saying anything.
         */
        const val TYPE_GROUP_PROFILE_SIGNED = "groupProfileSigned"

        /**
         * The group signed where it lives on nostr.
         *
         * One line per list, so a session that changed three of them writes three.
         * They are not three accounts of one thing -- each names which list was
         * agreed, which is the part a reader needs -- and `applyInnerEvent` is
         * handed one event at a time with no way to know it was in a batch anyway.
         */
        const val TYPE_GROUP_RELAYS_SIGNED = "groupRelaysSigned"

        /**
         * Every line about the group's own nostr identity, for the one check the
         * transcript dispatches on.
         *
         * The profile and the relay lists together, because they are the same kind
         * of statement -- the group's account of itself, signed by the group -- and
         * the transcript does the same thing with both. A type missing from here
         * renders as a chat bubble, silently, looking exactly like somebody having
         * said "The group is now called Translation collective".
         */
        /**
         * The group signed something to say publicly, in its own name.
         *
         * A system line rather than a bubble, like the other two -- nobody said it,
         * the group signed it -- and the line names the post rather than repeating
         * it, because the post itself is on the group's screen where it can be read
         * whole. A quote here would be the same words twice with the second copy
         * unable to say who agreed to them.
         */
        const val TYPE_GROUP_POST_SIGNED = "groupPostSigned"

        /**
         * The group signed the schema of a list it curates.
         *
         * A system line like the other three, and it names the list rather than
         * describing the schema: what a reader of the room needs to know is that
         * the group now curates -- or has changed the form for -- a list called
         * so-and-so, and the schema itself is on the group's screen where its
         * fields can be read.
         */
        const val TYPE_GROUP_SCHEMA_SIGNED = "groupSchemaSigned"

        /**
         * The group signed an entry into a list it curates.
         *
         * A system line like the schema's, and it names the entry and the list
         * rather than the fields: what a reader of the room needs to know is
         * that the list now stands behind a title, and the entry itself is on
         * the queue screen where its fields can be read.
         */
        const val TYPE_GROUP_ENTRY_CURATED = "groupEntryCurated"

        val GROUP_IDENTITY_TYPES = setOf(
            TYPE_GROUP_PROFILE_SIGNED,
            TYPE_GROUP_RELAYS_SIGNED,
            TYPE_GROUP_POST_SIGNED,
            TYPE_GROUP_SCHEMA_SIGNED,
            TYPE_GROUP_ENTRY_CURATED,
        )

        /**
         * Every subgroup line, for the one check the transcript dispatches on.
         *
         * One member today, and a set anyway. A type missing from here renders as
         * a chat bubble -- silently, and looking exactly like a member having said
         * "The group made Translation team a subgroup".
         */
        val SUBGROUP_TYPES = setOf(
            TYPE_SUBGROUP_CERTIFIED,
        )

        /**
         * Every chronicle line, for the one check the transcript dispatches on.
         *
         * A type missing from here renders as a chat bubble -- silently, and
         * looking exactly like a member having said "Caught up on 12 items".
         */
        val CHRONICLE_TYPES = setOf(
            TYPE_CHRONICLE_REQUESTED,
            TYPE_CHRONICLE_SENT,
            TYPE_CHRONICLE_RECEIVED,
        )

        /**
         * Adding a member to the group, as lines in the group's chat.
         *
         * An invite is two separate things, and in an established group they can be
         * minutes apart: the membership change this device commits, and the Welcome
         * that lets the invitee in going on the wire. The second waits on a relay
         * acknowledging the commit -- see docs/marmot-membership.md -- and it is the
         * one that can fail after the screen that asked for it has closed, which
         * leaves the transcript as the only place able to say so.
         *
         * So [TYPE_MEMBER_INVITED] is written the moment the invite is made, and
         * [TYPE_MEMBER_INVITE_SENT] only where the Welcome is delivered later than
         * that. Where the two happen together -- a group that is still only its
         * creator has nobody to inform, so its Welcome goes out immediately -- there
         * is one line, because there was one event.
         *
         * Written by the inviter's device, for itself. None of these travels: what
         * the group receives is the commit, and each member's transcript is written
         * from what it made of that. A member watching from the side sees nothing
         * here, correctly.
         *
         * Content is a whole sentence rather than a predicate, so these stay out of
         * the AUTHORED sets and nothing prefixes a name to them. The invitee's name
         * is written into the content the way the chronicle's is, which does not
         * follow a rename and is the accepted cost for a line about a thing that
         * happened once.
         */
        const val TYPE_MEMBER_INVITED = "memberInvited"
        const val TYPE_MEMBER_INVITE_SENT = "memberInviteSent"
        const val TYPE_MEMBER_INVITE_FAILED = "memberInviteFailed"

        /**
         * What the group's state said changed, as lines in the group's chat.
         *
         * Derived, never received. v2 spells these as kind `1210` system rows and
         * is explicit that a row which arrives over the wire is one member's
         * assertion, not a fact; a row derived from the commit this device applied
         * cannot be forged by one member, and every device applying that commit
         * derives the same one. So these are written by `processGroupMembershipChanges`
         * from the difference between the room's members before a commit and after
         * it, on every device that applies it -- and by the device that made the
         * change, for what it did. See docs/marmot-v2.md, Phase 7.
         *
         * [TYPE_MEMBER_LEFT] covers a member who left and one an admin removed
         * alike: the tree does not say which, and a line that guessed would be a
         * caption no other device writes.
         */
        const val TYPE_MEMBER_ADDED = "memberAdded"
        const val TYPE_MEMBER_LEFT = "memberLeft"
        const val TYPE_GROUP_DISBANDED = "groupDisbanded"

        /**
         * A change this device asked for that every relay refused, so the line
         * written when it was asked for -- a removal, a disband -- did not come
         * true. The invite's own version is [TYPE_MEMBER_INVITE_FAILED].
         */
        const val TYPE_CHANGE_REJECTED = "changeRejected"

        /**
         * Every membership line, for the one check the transcript dispatches on.
         *
         * A type missing from here renders as a chat bubble -- silently, and looking
         * exactly like the inviter having said "Invited Bob to the group".
         */
        val MEMBERSHIP_TYPES = setOf(
            TYPE_MEMBER_INVITED,
            TYPE_MEMBER_INVITE_SENT,
            TYPE_MEMBER_INVITE_FAILED,
            TYPE_MEMBER_ADDED,
            TYPE_MEMBER_LEFT,
            TYPE_GROUP_DISBANDED,
            TYPE_CHANGE_REJECTED,
        )

        const val TYPE_UNDECRYPTABLE_OUTER_LAYER = "undecryptableOuterLayer"
        const val TYPE_PENDING_COMMIT = "pendingCommit"

        /**
         * The lines a reindex is allowed to replace.
         *
         * A group event that left one of these behind was never read, so replaying
         * it can only improve on what is there. Every other line -- a message, an
         * applied commit, anything this device sent -- is the final word on its
         * group event and a replay must leave it alone.
         */
        val UNRESOLVED_MARMOT_TYPES = setOf(
            TYPE_UNDECRYPTABLE_OUTER_LAYER,
            TYPE_PENDING_COMMIT,
        )

        /**
         * The row to write for a group event that was just read, given the line that
         * event already has. Null means leave what is there alone.
         *
         * [ChatMessage.id] is autogenerated, so writing [fresh] as it comes always
         * inserts. That is right the first time a group event is read and wrong every
         * time after: a replay would leave the room showing each recovered message
         * twice, once as "Undecryptable Message" and once as itself. A group event has
         * exactly one line and a deterministic id, so [existing] is what makes a
         * re-read an update instead.
         *
         * A placeholder never overwrites a line that says something. A group event
         * this device sent already has its line, written on the way out, and a replay
         * that cannot read back our own ratchet-consumed message must not replace it
         * with "Undecryptable Message".
         *
         * [viewedAt] and [savedAt] stay with [existing]: when a line was first seen is
         * a fact about the reader, not about the event, and a re-read is not a new
         * arrival. Its id stays too, so the broadcast and nostr-event relations
         * pointing at that row keep pointing at it.
         */
        fun reconcileMarmotLine(
            fresh: ChatMessage,
            existing: ChatMessage?,
        ): ChatMessage? {
            if (existing == null) return fresh

            val freshIsPlaceholder = fresh.messageType in UNRESOLVED_MARMOT_TYPES
            val existingIsPlaceholder = existing.messageType in UNRESOLVED_MARMOT_TYPES

            if (freshIsPlaceholder && !existingIsPlaceholder) return null

            return fresh.copy(
                id = existing.id,
                savedAt = existing.savedAt,
                viewedAt = existing.viewedAt,
            )
        }

        /**
         * The request lines this device has since answered, by row id.
         *
         * A request is answered when the step it asked for has been published by
         * this device -- which is exactly what approving it does. The transcript
         * already records that as an authored line, so the answer is read from
         * the list rather than from the session, and it stays right for a room
         * that has run more than one ceremony.
         */
        fun answeredRequests(messages: List<ChatMessage>): Set<Long> = messages
            .mapNotNull { request ->
                val published = (DKG_REQUEST_FULFILMENTS + FROST_REQUEST_FULFILMENTS)[request.messageType]
                    ?: return@mapNotNull null

                val done = messages.any {
                    it.messageType == published &&
                        it.isUserMessage &&
                        it.isAbout(request)
                }

                request.id.takeIf { done }
            }
            .toSet()

        /**
         * Whether this line is part of the same piece of business as [request].
         *
         * A signing line says which session it belongs to, and that answer is
         * exact: two sessions running at once are two separate decisions, and
         * one of them ending says nothing about the other.
         *
         * Everything else falls back to the clock, which is what the transcript
         * always used. It is right for a ceremony -- a room runs one at a time --
         * and it is what a line written before [frostSigningSessionId] existed
         * has to be read by.
         */
        private fun ChatMessage.isAbout(request: ChatMessage): Boolean = when {
            frostSigningSessionId != null && request.frostSigningSessionId != null ->
                frostSigningSessionId == request.frostSigningSessionId

            // Ceremonies say which one they are now too. The clock used to be
            // right here because a room ran one at a time; a room that holds a
            // group's own ceremony and a subgroup's would otherwise credit an
            // answer given to one as an answer to the other.
            dkgSessionId != null && request.dkgSessionId != null ->
                dkgSessionId == request.dkgSessionId

            else -> createdAt >= request.createdAt
        }

        /**
         * The signing requests that are no longer open, by row id.
         *
         * Not the same thing as [answeredRequests], and deliberately kept apart
         * from it: these are requests the reader never answered, so they have
         * earned no tick and claiming otherwise would credit them with a
         * signature they refused or were never needed for. What they have run
         * out of is a decision to make -- see [FROST_SETTLEMENTS].
         *
         * The reader's own refusal settles their request too, and only theirs.
         * The session is still open -- the others may yet sign it -- so it is
         * not an ending, and another member's refusal leaves the reader with
         * exactly the decision they had.
         */
        fun settledRequests(messages: List<ChatMessage>): Set<Long> = messages
            .filter { request ->
                request.messageType in FROST_REQUEST_TYPES &&
                    messages.any { it.settles() && it.isAbout(request) }
            }
            .map { it.id }
            .toSet()

        private fun ChatMessage.settles(): Boolean =
            messageType in FROST_SETTLEMENTS ||
                (messageType == TYPE_FROST_DECLINED && isUserMessage)

        /**
         * Files one direct message, from whichever side of it this device is on.
         *
         * The decision is [MarmotDirectMessage.classify]'s, which is where it is tested;
         * this turns it into rows. See docs/marmot-direct-messages.md.
         *
         * [filedAs] is the id of the `MarmotInnerEvent` row the caller wrote for the
         * payload as it arrived -- the wrap's own id in a legacy room, the envelope's in
         * a current-profile one. The line for a message this device cannot read has to
         * hang off a row that exists, and `ChatMessage.marmotInnerEventId` is a foreign
         * key that says so.
         */
        private suspend fun directMessage(
            database: CurareDatabase,
            activeKeyPair: KeyPair,
            groupEvent: GroupEvent,
            chatRoomId: String,
            wrap: Event,
            filedAs: HexKey,
            senderIdentity: HexKey?
        ): ChatMessage? {
            val recipientPublicKey = wrap.tags.firstTagValue("p")

            return when (val delivery = MarmotDirectMessage.classify(wrap, activeKeyPair, senderIdentity)) {
                // Our own words coming back. We cannot open our own wrap -- its key was
                // discarded at send time -- so this is indistinguishable from a
                // bystander's view by decryption alone, and filing it as one would
                // replace the words on the row `sendChatMessage` wrote with an empty
                // placeholder. That row is the only copy of them that exists.
                // `NostrDao.persistInboundChatMessage` carries the same guard, for the
                // same reason, on the NIP-17 path.
                MarmotDirectMessage.Delivery.Ours -> {
                    logger.d("Direct message ${wrap.id} is ours; the row written on send is the only copy")
                    null
                }

                // A forged or malformed message costs its own line and nothing else. It
                // is dropped rather than thrown because the caller is inside
                // storeNostrEvent's transaction, and this must not cost the whole event.
                is MarmotDirectMessage.Delivery.Rejected -> {
                    logger.e("Dropping direct message ${wrap.id}: ${delivery.reason}")
                    null
                }

                // Not ours to read. The group is meant to see that a private message was
                // sent and to whom, and nothing more.
                MarmotDirectMessage.Delivery.Unreadable -> {
                    // Relays redeliver and negentropy re-syncs; the wrap yields the same
                    // id every time, so this is what keeps a second delivery from becoming
                    // a second line. ChatMessage.id is autogenerated and would take a
                    // duplicate.
                    if (database.chatMessageDao().getChatMessagesByMarmotInnerEventId(filedAs) != null) {
                        return null
                    }

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_DIRECT_MESSAGE,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = filedAs,
                        senderPublicKey = senderIdentity ?: wrap.pubKey,
                        directMessageRecipientPublicKey = recipientPublicKey,
                        isUserMessage = false,
                        chatRoomId = chatRoomId,
                        createdAt = Instant.fromEpochSeconds(wrap.createdAt),
                        content = ""
                    )
                }

                is MarmotDirectMessage.Delivery.Readable -> {
                    val opened = delivery.opened

                    if (database.chatMessageDao().getChatMessagesByMarmotInnerEventId(opened.rumor.id) != null) {
                        return null
                    }

                    // Keyed on the rumor, not the wrap. This is the id the sender queued,
                    // so both sides of the conversation hold the same message under the
                    // same identity.
                    database.marmotInnerEventDao().upsert(
                        MarmotInnerEvent(
                            id = opened.rumor.id,
                            publicKey = opened.rumor.pubKey,
                            marmotGroupEventId = groupEvent.id,
                            tags = opened.rumor.tags,
                            content = opened.rumor.content,
                            chatRoomId = chatRoomId,
                            kind = opened.rumor.kind,
                            createdAt = Instant.fromEpochSeconds(opened.rumor.createdAt)
                        )
                    )

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_DIRECT_MESSAGE,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = opened.rumor.id,
                        senderPublicKey = opened.seal.pubKey,
                        directMessageRecipientPublicKey = recipientPublicKey,
                        isUserMessage = false,
                        chatRoomId = chatRoomId,
                        createdAt = Instant.fromEpochSeconds(opened.rumor.createdAt),
                        content = opened.rumor.content
                    )
                }
            }
        }

        /**
         * [senderIdentity] is the MLS credential identity of the leaf that sent this,
         * resolved by the caller, which holds the group. It is not derivable from the
         * payload: a direct message's wrap is keyed to a throwaway key and names nobody,
         * and even for kinds that do carry a pubkey the MLS frame is the authenticated
         * source while the field is merely asserted.
         *
         * It is also the only thing [ChatMessage.isUserMessage] can honestly be computed
         * from. Every arm below used to compare the active key against `groupEvent.pubKey`
         * -- the kind:445's signer, which is a fresh random ephemeral key on every send
         * (`NostrSignerInternal(KeyPair())` in `encryptAndSendMarmotInnerEvent`). That can
         * never equal anybody's identity, so it was false for every Marmot message in
         * every room, and every one of them rendered as somebody else's.
         *
         * Null for results that carry no leaf index -- the commit and proposal statuses --
         * which yields false, exactly as before.
         */
        suspend fun fromGroupEventResult(
            database: CurareDatabase,
            activeKeyPair: KeyPair,
            groupEvent: GroupEvent,
            groupEventResult: GroupEventResult,
            senderIdentity: HexKey? = null
        ): ChatMessage? {
            return when(groupEventResult) {
                is GroupEventResult.ApplicationMessage -> {
                    val event = Event.fromJsonOrNull(groupEventResult.innerEventJson)
                        ?: throw MarmotUnprocessableInnerEventException("Can't process ${groupEventResult.innerEventJson}")

                    // A submission is an envelope: the nip30303 event it delivers
                    // is in its content, written by whoever wrote it. Everything
                    // else on the wire is the nip30303 event itself. Either way the
                    // row on disk is the outer event -- that is what the group sent
                    // and what the chat line is attributed to.
                    val submission = if (event.kind == SubmissionEvent.KIND) {
                        SubmissionEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig,
                        )
                    } else {
                        null
                    }
                    val payload = submission?.payload()

                    database.marmotInnerEventDao().upsert(
                        MarmotInnerEvent(
                            id = event.id,
                            publicKey = event.pubKey,
                            marmotGroupEventId = groupEvent.id,
                            tags = event.tags,
                            content = event.content,
                            chatRoomId = groupEventResult.groupId,
                            kind = event.kind,
                            payloadEventId = payload?.id,
                            createdAt = Instant.fromEpochSeconds(event.createdAt)
                        )
                    )

                    // A direct message is not a nip30303 payload to apply. It is
                    // gift wrapped for one member, and what this device does with it
                    // depends only on whether its key opens it. A legacy room carries
                    // the wrap itself; a current-profile room carries it inside an
                    // envelope authored by the sender -- see MarmotDirectMessage. The
                    // row above was filed under the payload's own id either way, and
                    // that is the id the line for a message we cannot read hangs off.
                    if (event.kind == GiftWrapEvent.KIND) {
                        return directMessage(
                            database = database,
                            activeKeyPair = activeKeyPair,
                            groupEvent = groupEvent,
                            chatRoomId = groupEventResult.groupId,
                            wrap = event,
                            filedAs = event.id,
                            senderIdentity = senderIdentity
                        )
                    }

                    if (event.kind == MarmotDirectMessage.ENVELOPE_KIND) {
                        val wrap = MarmotDirectMessage.wrapInside(event)
                        if (wrap == null) {
                            logger.e("Dropping direct message envelope ${event.id}: it holds no wrap")
                            return null
                        }
                        return directMessage(
                            database = database,
                            activeKeyPair = activeKeyPair,
                            groupEvent = groupEvent,
                            chatRoomId = groupEventResult.groupId,
                            wrap = wrap,
                            filedAs = event.id,
                            senderIdentity = senderIdentity
                        )
                    }

                    // A chronicle is neither a document nor a submission: it is a
                    // bundle of documents addressed to one member who is missing
                    // them. Intercepted here rather than in applyInnerEvent for
                    // the same reason the gift wrap above is -- deciding whether
                    // to act needs the active key, which applyInnerEvent has no
                    // business knowing.
                    if (event.kind == ChronicleEvent.KIND) {
                        ChronicleManager.receive(
                            database = database,
                            chatRoomId = groupEventResult.groupId,
                            userPublicKey = activeKeyPair.pubKey.toHex(),
                            page = event,
                        )

                        // No chat line, and not for want of one worth writing.
                        // One line per chronicle is right; one per page is not, and
                        // the pages of a chronicle are not distinguishable from
                        // each other here. That is Phase 7's, and until then a
                        // silent catch-up beats a transcript full of envelopes.
                        return null
                    }

                    // Somebody in the room holds none of its signed work and is
                    // asking. Any member may answer and none is elected to: a
                    // duplicate answer costs bandwidth and nothing else, since
                    // pages are idempotent and everyone but the recipient ignores
                    // them. A member with nothing signed answers nothing, which
                    // is the honest reply from one still catching up themselves.
                    if (event.kind == ChronicleRequestEvent.KIND) {
                        ChronicleManager.sendTo(
                            database = database,
                            chatRoomId = groupEventResult.groupId,
                            userPublicKey = activeKeyPair.pubKey.toHex(),
                            // The MLS frame is the authenticated source. For these
                            // kinds MIP-03 already forces the two to agree, so the
                            // fallback is for a result that carries no leaf index.
                            recipient = senderIdentity ?: event.pubKey,
                        )

                        return null
                    }

                    // A submission whose payload will not parse, or which carries
                    // another submission, is kept but not applied: there is nothing
                    // here we can turn into a row.
                    if (submission != null && (payload == null || payload.kind == SubmissionEvent.KIND)) {
                        ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = "unsupported",
                            marmotGroupEventId = groupEvent.id,
                            marmotInnerEventId = event.id,
                            senderPublicKey = event.pubKey,
                            isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                            chatRoomId = groupEventResult.groupId,
                            createdAt = Instant.fromEpochSeconds(event.createdAt),
                            content = event.content,
                        )
                    } else {
                        applyInnerEvent(
                            database = database,
                            groupId = groupEventResult.groupId,
                            event = payload ?: event,
                            marmotGroupEventId = groupEvent.id,
                            marmotInnerEventId = event.id,
                            senderPublicKey = event.pubKey,
                            isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                            createdAt = Instant.fromEpochSeconds(event.createdAt),
                        )
                    }
                }
                is GroupEventResult.CommitPending -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = TYPE_PENDING_COMMIT,
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Pending Commit in epoch ${groupEventResult.epoch}", // TODO: Figure out what to do here...
                    )
                }
                is GroupEventResult.CommitProcessed -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = "processedCommit",
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Processed Commit in epoch ${groupEventResult.newEpoch}", // TODO: Figure out what to do here...
                    )
                }
                is GroupEventResult.Duplicate -> {

//                    ChatMessage(
//                        giftWrapPayloadId = null,
//                        groupEventId = groupEvent.id,
//                        messageType = "duplicate",
//                        senderPublicKey = groupEvent.pubKey,
//                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
//                        chatRoomId = groupEventResult.groupId,
//                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
//                        content = groupEventResult.innerEventJson, // TODO: Figure out what to do here...
//                    )
                    null
                }
                is GroupEventResult.Error -> {
//                    ChatMessage(
//                        giftWrapPayloadId = null,
//                        groupEventId = groupEvent.id,
//                        messageType = "error",
//                        senderPublicKey = groupEvent.pubKey,
//                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
//                        chatRoomId = groupEventResult.groupId,
//                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
//                        content = groupEventResult.innerEventJson, // TODO: Figure out what to do here...
//                    )
                    null
                }
                is GroupEventResult.ProposalStaged -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = "proposalStaged",
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Proposal staged: ${groupEventResult.senderLeafIndex}", // TODO: Figure out what to do here...
                    )
                }
                is GroupEventResult.UndecryptableOuterLayer -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        marmotGroupEventId = groupEvent.id,
                        marmotInnerEventId = null,
                        messageType = TYPE_UNDECRYPTABLE_OUTER_LAYER,
                        senderPublicKey = groupEvent.pubKey,
                        isUserMessage = senderIdentity == activeKeyPair.pubKey.toHex(),
                        chatRoomId = groupEventResult.groupId,
                        createdAt = Instant.fromEpochSeconds(groupEvent.createdAt),
                        content = "Undecryptable Message", // TODO: Figure out what to do here...
                    )
                }
                // Neither is a delivery. quartz 1.15.2's inbound processor produces both
                // from its convergence engine: the first is a payload that decrypted only
                // on a losing fork, which the library is explicit must not be rendered,
                // and the second is input a disbanded or unrecoverable group refuses
                // outright. [MarmotInboundManager] drives MlsGroup itself and never
                // constructs either, so today these are unreachable; if it adopts the
                // engine they get no chat line, the same as a duplicate.
                is GroupEventResult.AppMessageOnCandidateBranch -> null
                is GroupEventResult.RefusedByLifecycle -> null
            }
        }

        /**
         * Apply one nip30303 [event] the group delivered, and describe it for
         * the transcript.
         *
         * [event] is what is being applied; the other parameters are how it
         * arrived. They come apart for submissions: the payload is written by
         * whoever wrote it -- possibly nobody in this group -- while
         * [marmotInnerEventId], [senderPublicKey] and [createdAt] all belong to
         * the envelope a member actually sent. Entity rows take their author
         * from [event], so the chat line says who added it and the row says who
         * wrote it.
         *
         * [groupSignedEventId] is the third way in, and the one with no
         * envelope at all: a `GroupSignedEvent` the group put its own signature
         * to, applied by `FrostSigningManager` when a session completes or by
         * `ChronicleManager` when a page arrives. Both Marmot ids are null for
         * those -- there is no group event and no inner event behind them --
         * which is why they need an id of their own, and why every row this
         * writes carries it.
         */
        internal suspend fun applyInnerEvent(
            database: CurareDatabase,
            groupId: String,
            event: Event,
            marmotGroupEventId: HexKey?,
            marmotInnerEventId: HexKey?,
            senderPublicKey: HexKey,
            isUserMessage: Boolean,
            createdAt: Instant,
            groupSignedEventId: HexKey? = null,
        ): ChatMessage? {
            // The last resort, named because one arm reaches it deliberately rather
            // than by falling off the end: a kind:1 the room did not sign is a
            // member's own note travelling through the room, and it has always
            // rendered as this. See the `TextNoteEvent` arm.
            fun unsupported() = ChatMessage(
                giftWrapPayloadId = null,
                messageType = "unsupported",
                marmotGroupEventId = marmotGroupEventId,
                marmotInnerEventId = marmotInnerEventId,
                senderPublicKey = senderPublicKey,
                isUserMessage = isUserMessage,
                chatRoomId = groupId,
                createdAt = createdAt,
                content = event.toJson(),
            )

            return when (event.kind) {
                ChatEvent.KIND -> {
                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = "message",
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = event.content, // TODO: Figure out what to do here...
                    )
                }
                ArtifactEvent.KIND -> {
                    val artifactEvent = ArtifactEvent(
                        id = event.id,
                        pubKey = event.pubKey,
                        createdAt = event.createdAt,
                        tags = event.tags,
                        content = event.content,
                        sig = event.sig,
                    )

                    MantraArtifact.fromArtifactEvent(
                        artifactEvent = artifactEvent,
                        chatRoomId = groupId,
                    )?.let { mantraArtifact ->
                        database.mantraArtifactDao().upsert(
                            mantraArtifact.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )

                        // The version the artifact starts life with is its own
                        // event, signed in the same batch and applied after this
                        // one -- see AddArtifactViewModel.addArtifact. It used to
                        // be derived here instead; deriving it now as well would
                        // stand a second, unsigned version row against the same
                        // artifact, since the two hash differently.

                        ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = "artifact",
                            marmotGroupEventId = marmotGroupEventId,
                            marmotInnerEventId = marmotInnerEventId,
                            senderPublicKey = senderPublicKey,
                            isUserMessage = isUserMessage,
                            chatRoomId = groupId,
                            createdAt = createdAt,
                            content = "Added ${mantraArtifact.name} to artifacts"
                        )
                    }
                }
                ArtifactVersionEvent.KIND -> {
                    MantraArtifactVersion.fromArtifactVersionEvent(
                        artifactVersionEvent = ArtifactVersionEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraArtifactVersion ->
                        database.mantraArtifactVersionDao().upsert(
                            mantraArtifactVersion.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )

                        // No line of its own. A version arrives as the second
                        // item of the batch that carries its artifact, and the
                        // artifact has already said so -- the same reason a
                        // chunk writes no line beside its chapter. While the
                        // version was derived here rather than signed it wrote
                        // none either, so this is the transcript standing still
                        // rather than losing something.
                    }
                    null
                }
                ChapterEvent.KIND -> {
                    // The chunks the chapter splits into are their own events,
                    // signed in the same session and applied after it -- see
                    // AddChapterViewModel.addChapter. Nothing to do here but the
                    // chapter itself.
                    MantraChapter.fromChapterEvent(
                        chapterEvent = ChapterEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraChapter ->
                        database.mantraChapterDao().upsert(
                            mantraChapter.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )

                        ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = "chapter",
                            marmotGroupEventId = marmotGroupEventId,
                            marmotInnerEventId = marmotInnerEventId,
                            senderPublicKey = senderPublicKey,
                            isUserMessage = isUserMessage,
                            chatRoomId = groupId,
                            createdAt = createdAt,
                            content = "Added ${mantraChapter.name} to chapters" // TODO: Use artifact name...
                        )
                    }
                }

                ChunkEvent.KIND -> {
                    MantraChunk.fromChunkEvent(
                        chunkEvent = ChunkEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraChunk ->
                        database.mantraChunkDao().upsert(
                            mantraChunk.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )

                        // TODO: Chunks might be too noisy to show in chat...
                    }
                    null
                }
                DialectEvent.KIND -> {
                    MantraDialect.fromDialectEvent(
                        dialectEvent = DialectEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraDialect ->
                        database.mantraDialectDao().upsert(
                            mantraDialect.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )


                        ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = "dialect",
                            marmotGroupEventId = marmotGroupEventId,
                            marmotInnerEventId = marmotInnerEventId,
                            senderPublicKey = senderPublicKey,
                            isUserMessage = isUserMessage,
                            chatRoomId = groupId,
                            createdAt = createdAt,
                            content = "Added ${mantraDialect.name} to dialects" // TODO: Use artifact name...
                        )
                    }
                }
                TranslationArtifactVersionEvent.KIND -> {
                    MantraTranslationArtifactVersion.fromTranslationArtifactVersionEvent(
                        translationArtifactVersionEvent = TranslationArtifactVersionEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraTranslationArtifactVersion ->
                        database.mantraTranslationArtifactVersionDao().upsert(
                            mantraTranslationArtifactVersion.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )

                        ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = "translationArtifactVersion",
                            marmotGroupEventId = marmotGroupEventId,
                            marmotInnerEventId = marmotInnerEventId,
                            senderPublicKey = senderPublicKey,
                            isUserMessage = isUserMessage,
                            chatRoomId = groupId,
                            createdAt = createdAt,
                            content = "Added ${mantraTranslationArtifactVersion.name} to translation artifact versions" // TODO: Use artifact name...
                        )
                    }
                }
                TranslationArtifactVersionContributorListEvent.KIND -> {
                    // TODO: Consume contributors...
                    null
                }
                TranslationChapterEvent.KIND -> {
                    MantraTranslationChapter.fromTranslationChapterEvent(
                        translationChapterEvent = TranslationChapterEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraTranslationChapter ->
                        database.mantraTranslationChapterDao().upsert(
                            mantraTranslationChapter.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )
                        // TODO: translation chapter might be too noisy for chat updates
                    }
                    null
                }
                TranslationChunkEvent.KIND -> {
                    MantraTranslationChunk.fromTranslationChunkEvent(
                        translationChunkEvent = TranslationChunkEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraTranslationChunk ->
                        // One translation per source chunk. Retranslating changes
                        // the text and so the event's id, which makes it a new
                        // event rather than an edit of the old one -- so the one
                        // it supersedes is dropped here, or a passage would have
                        // two answers to what it says and the reader would be
                        // shown whichever the query happened to reach first.
                        //
                        // Newest wins by the timestamp the group signed at, not by
                        // when it arrived, with the id breaking a tie. Two devices
                        // catching up read the same events in whatever order the
                        // relay hands them over, and they have to end up holding
                        // the same translation either way.
                        val existing = database.mantraTranslationChunkDao()
                            .getTranslationChunksByChunkId(
                                translationChapterId = mantraTranslationChunk.translationChapterId,
                                chunkId = mantraTranslationChunk.chunkId,
                            )
                            .filterNot { it.id == mantraTranslationChunk.id }

                        val isNewest = existing.none {
                            it.createdAt > mantraTranslationChunk.createdAt ||
                                (it.createdAt == mantraTranslationChunk.createdAt &&
                                    it.id > mantraTranslationChunk.id)
                        }

                        if (isNewest) {
                            existing.forEach {
                                database.mantraTranslationChunkDao().deleteById(it.id)
                            }

                            database.mantraTranslationChunkDao().upsert(
                                mantraTranslationChunk.copy(
                                    marmotGroupEventId = marmotGroupEventId,
                                    groupSignedEventId = groupSignedEventId,
                                )
                            )
                        }
                    }
                    null
                }
                TranslationContributorListEvent.KIND -> {
                    // TODO: Consume contributors...
                    null
                }
                TranslationEvent.KIND -> {
                    MantraTranslation.fromTranslationEvent(
                        translationEvent = TranslationEvent(
                            id = event.id,
                            pubKey = event.pubKey,
                            createdAt = event.createdAt,
                            tags = event.tags,
                            content = event.content,
                            sig = event.sig
                        ),
                        chatRoomId = groupId,
                    )?.let { mantraTranslation ->
                        database.mantraTranslationDao().upsert(
                            mantraTranslation.copy(
                                marmotGroupEventId = marmotGroupEventId,
                                groupSignedEventId = groupSignedEventId,
                            )
                        )
                        ChatMessage(
                            giftWrapPayloadId = null,
                            messageType = "translation",
                            marmotGroupEventId = marmotGroupEventId,
                            marmotInnerEventId = marmotInnerEventId,
                            senderPublicKey = senderPublicKey,
                            isUserMessage = isUserMessage,
                            chatRoomId = groupId,
                            createdAt = createdAt,
                            content = "Added ${mantraTranslation.text} to translations" // TODO: Reference original text
                        )
                    }
                }
                // A signing session's own protocol messages. FrostSigningManager
                // applies them and writes its own transcript lines naming who did
                // what, so an "unsupported" row here would be a second, worse
                // account of the same thing.
                in FrostSigningEvents.ALL -> null

                // A key ceremony's own protocol messages, for the same reason.
                // These reach a Marmot room when the group is standing up a
                // subgroup: the child's ChillDKG runs in the parent's room,
                // because the child has no room until the ceremony has made the
                // key its id is derived from. `ChillDkgRitualManager` applies
                // them and writes the ladder of lines naming who joined, who
                // contributed and who confirmed.
                //
                // Null for every member of the room, including the ones the
                // ceremony is not with. They were never in the participant set
                // and their device drops the proposal, so the alternative to no
                // line at all is an "unsupported" one for a ceremony that is
                // none of their business.
                in DkgRitualEvents.ALL -> null

                // The group saying what key one of its rooms signs with, arriving
                // here the way every group-signed event does: applied by
                // `FrostSigningManager` once a quorum has signed the proposal it
                // was carried in.
                //
                // [groupId] is where the signing happened, which is usually not
                // the room the state is about -- a group agrees this before the
                // room exists, so there is routinely nothing to write yet and
                // `GroupKeyStateManager.adopt` files it when the room appears.
                //
                // Recorded rather than believed either way. `GroupKeyStateManager`
                // keeps a state only if the room it *names* rederives from the key
                // it names, and the group holding that key signed it, so a state
                // reaching this line still has to earn the row.
                //
                // No chat line: this is standing state rather than something
                // that happened, and the session that produced it has already
                // written the transcript of it happening. Falling through to
                // "unsupported" would put the raw event in the chat underneath
                // that.
                GroupKeyStateEvent.KIND -> {
                    GroupKeyStateManager.record(
                        database = database,
                        chatRoomId = groupId,
                        innerEvent = event
                    )
                    null
                }

                // The group has certified another group as its child. Verified
                // again here rather than trusted: this arm is reached both from a
                // completed signing session, where the signature has already been
                // checked, and from an arriving inner event, where a member could
                // have sent a rumor of this kind -- and it cannot tell which.
                //
                // Unlike the key state above this *does* write a line. A key state
                // is standing state whose session already wrote the transcript; a
                // subgroup being born is something that happened, and it happened
                // on behalf of members of this room who are not in the child and
                // will otherwise never be told it exists.
                //
                // The name is the one the parent certified, which is the only one
                // that was ever put to this room -- the child may since have been
                // renamed, and this line is a record of what was agreed rather
                // than a view of what is.
                SubgroupBirthCertificateEvent.KIND -> {
                    SubgroupManager.record(
                        database = database,
                        chatRoomId = groupId,
                        innerEvent = event
                    ) ?: return null

                    val name = SubgroupBirthCertificateEvent.parseName(event.tags)
                    val admins = SubgroupBirthCertificateEvent.parseAdminPublicKeys(event.tags).size

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_SUBGROUP_CERTIFIED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group made ${name ?: "a subgroup"} a subgroup, " +
                            "run by $admins of its members"
                    )
                }

                // The group saying who it is on nostr. A room's id is the pubkey
                // it signs as, so a group has an identity from the moment it has a
                // key; this is the kind:0 describing it, signed by the group's own
                // quorum like everything else it says.
                //
                // **Only when the room itself signed it.** This arm is reached both
                // from a completed signing session, where the author is the room by
                // construction, and from an arriving inner event, where any member
                // could have sent a rumor of this kind -- and it cannot tell which.
                // A member's own kind:0 travelling through a room is their profile,
                // not the group's, and gets no line here.
                //
                // No row is written. The event is already on file as a
                // `GroupSignedEvent` -- `FrostSigningManager.complete` records the
                // batch before it applies it -- and `GroupNostrProfile` reads the
                // group's profile straight off those. A `Profile` row is not an
                // option anyway: it hangs off `NostrEvent` by foreign key, and a
                // group-signed event is not one.
                MetadataEvent.KIND -> {
                    if (!event.pubKey.equals(groupId, ignoreCase = true)) return null

                    val profile = GroupNostrProfile.of(
                        signedEvent = GroupSignedEvent.fromEvent(event, chatRoomId = groupId),
                        chatRoomId = groupId,
                    ) ?: return null

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_PROFILE_SIGNED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = profile.name()?.let { "The group is now called $it" }
                            ?: "The group signed a new profile for itself"
                    )
                }

                // The group saying something publicly, in its own name -- which
                // is a different thing from a member saying something in the room.
                // A room's messages are `ChatEvent`s; this is a kind:1 authored by
                // the room's own key, so any reader can check the group said it and
                // that no single member could have.
                //
                // Verified like the arms below and for the same reason: a rumor
                // carries an empty signature and any pubkey its sender likes, so an
                // author check alone would let one member put words in the group's
                // mouth in its own transcript.
                //
                // **Unlike those arms, a kind:1 that fails falls through to
                // `unsupported` rather than vanishing.** A member's kind:0 or relay
                // list passing through a room is identity plumbing nobody sent on
                // purpose and silence is the right treatment; a kind:1 is content,
                // somebody meant it, and it has rendered as an unsupported event
                // since before any of this existed. Dropping it here would be this
                // change quietly deleting messages it has no business touching.
                TextNoteEvent.KIND -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    if (!signed.verifies()) return unsupported()

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_POST_SIGNED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group posted in its own name"
                    )
                }

                // The group saying where it lives on nostr: which relays carry
                // its work, take its messages, answer a search of it, and which it
                // will not talk to.
                //
                // **Verified, not just attributed.** This arm is reached from a
                // completed signing session, where the author is the room by
                // construction, and from an arriving inner event, where it is
                // whatever the sender wrote -- and it cannot tell which. A rumor
                // carries an empty signature and any pubkey its sender likes, so an
                // author check alone would let one member put "the group signed its
                // general relay list" in the transcript. `verifies` is what makes
                // the line mean what it says: the room's key over these tags.
                //
                // A member's own relay list travelling through the room fails the
                // author half and gets no line, which is right -- it is their list,
                // not the group's.
                //
                // No row is written, and nothing is parsed beyond the check. The
                // events are already on file as `GroupSignedEvent` and
                // `GroupRelayList` reads the lists straight off those; this arm
                // exists so a signed relay list is a line in the transcript rather
                // than raw JSON in a bubble.
                in GroupRelaySet.KINDS -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    if (!signed.verifies()) return null

                    val set = GroupRelaySet.entries.firstOrNull { it.kind == event.kind }
                        ?: return null

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_RELAYS_SIGNED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group signed its ${set.name.lowercase()} relay list"
                    )
                }

                // The group defining a list it curates: the kind 31889 that
                // suggestions from anyone reply to and only the group's own key can
                // accept into the list.
                //
                // Verified and parsed, and null on either failing, the way the
                // profile arm is. A rumor of this kind from a member is not the
                // group curating anything, and a schema the room signed but that no
                // client could act on -- no visibility, no fields -- is not worth a
                // line saying it did. `GroupCuratedSchema` refuses the same event
                // on the group's screen, so the transcript and the screen agree.
                CuratedSchemaEvent.KIND -> {
                    val curated = GroupCuratedSchema.of(
                        signedEvent = GroupSignedEvent.fromEvent(event, chatRoomId = groupId),
                        chatRoomId = groupId,
                    ) ?: return null

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_SCHEMA_SIGNED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = "The group signed the curated schema for " +
                            curated.schema.displayName()
                    )
                }

                // The group taking an entry into a list it curates: the kind
                // 31890 that only its own key can publish, and the thing the
                // queue screen marks a suggestion "curated" by once it is read
                // back off a relay.
                //
                // Verified, for the reason the schema arm is: a rumor of this
                // kind from a member is not the group curating anything. Named
                // after the list where this device holds its schema -- looked
                // up by the coordinate the entry replies to -- and after the
                // entry alone where it does not, since the entry is what was
                // signed either way.
                CuratedEntryEvent.CANONICAL_KIND -> {
                    val signed = GroupSignedEvent.fromEvent(event, chatRoomId = groupId)
                    if (!signed.verifies()) return null

                    val entry = CuratedEntryEvent.headline(event)
                    val title = entry.title.ifBlank { entry.identifier }.ifBlank { "an entry" }

                    val list = runCatching {
                        GroupCuratedSchema.newestPerListAmong(
                            signedEvents = database.groupSignedEventDao()
                                .getByChatRoomIdAndKind(groupId, CuratedSchemaEvent.KIND),
                            chatRoomId = groupId,
                        ).firstOrNull { it.isRepliedToBy(event.tags) }
                    }.getOrNull()

                    ChatMessage(
                        giftWrapPayloadId = null,
                        messageType = TYPE_GROUP_ENTRY_CURATED,
                        marmotGroupEventId = marmotGroupEventId,
                        marmotInnerEventId = marmotInnerEventId,
                        senderPublicKey = senderPublicKey,
                        isUserMessage = isUserMessage,
                        chatRoomId = groupId,
                        createdAt = createdAt,
                        content = if (list != null) {
                            "The group accepted $title into ${list.schema.displayName()}"
                        } else {
                            "The group accepted $title into a list it curates"
                        }
                    )
                }

                // v2's two non-chat kinds. A kind:1210 that arrives is one member's
                // assertion about what the group's state became, and this device
                // derives that for itself from the commit it applied (see
                // [TYPE_MEMBER_ADDED]); a kind:1009 is an edit the app does not
                // apply. Neither is a line, and neither is a raw-JSON bubble from
                // a White Noise member. Every other unknown kind keeps the bubble,
                // which is the only trace an unrecognised payload leaves.
                MarmotAppEvent.KIND_SYSTEM, MarmotAppEvent.KIND_EDIT -> null

                else -> unsupported()
            }
        }
    }
}