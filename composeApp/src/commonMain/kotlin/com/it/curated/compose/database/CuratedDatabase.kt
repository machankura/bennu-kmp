package com.it.curated.compose.database

import androidx.room3.ColumnTypeConverters
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.it.curated.compose.database.converters.CuratedConverters
import com.it.curated.compose.database.dao.BroadcastNostrEventReceiptDao
import com.it.curated.compose.database.dao.BroadcastNostrEventRequestDao
import com.it.curated.compose.database.dao.ChatMessageBroadcastNostrEventReceiptRelationDao
import com.it.curated.compose.database.dao.ChatMessageBroadcastNostrEventRequestRelationDao
import com.it.curated.compose.database.dao.ChatMessageDao
import com.it.curated.compose.database.dao.ChatMessageNostrEventRelationDao
import com.it.curated.compose.database.dao.ChatRoomDao
import com.it.curated.compose.database.dao.ConnectionDao
import com.it.curated.compose.database.dao.DkgSessionDao
import com.it.curated.compose.database.dao.FrostSigningSessionDao
import com.it.curated.compose.database.dao.GiftWrapMessageDao
import com.it.curated.compose.database.dao.GiftWrapPayloadDao
import com.it.curated.compose.database.dao.GiftWrapSealDao
import com.it.curated.compose.database.dao.GroupKeyStateDao
import com.it.curated.compose.database.dao.GroupSignedEventDao
import com.it.curated.compose.database.dao.InReplyToRelationDao
import com.it.curated.compose.database.dao.MantraArtifactDao
import com.it.curated.compose.database.dao.MantraArtifactVersionDao
import com.it.curated.compose.database.dao.MantraChapterDao
import com.it.curated.compose.database.dao.MantraChunkDao
import com.it.curated.compose.database.dao.MantraDao
import com.it.curated.compose.database.dao.MantraDialectDao
import com.it.curated.compose.database.dao.MantraTranslationArtifactVersionDao
import com.it.curated.compose.database.dao.MantraTranslationArtifactVersionContributorDao
import com.it.curated.compose.database.dao.MantraTranslationChapterDao
import com.it.curated.compose.database.dao.MantraTranslationChapterContributorDao
import com.it.curated.compose.database.dao.MantraTranslationChunkDao
import com.it.curated.compose.database.dao.MantraTranslationDao
import com.it.curated.compose.database.dao.MantraTranslationContributorDao
import com.it.curated.compose.database.dao.MarmotCommitResultDao
import com.it.curated.compose.database.dao.MarmotOutboundDao
import com.it.curated.compose.database.dao.MarmotGroupEventDao
import com.it.curated.compose.database.dao.MarmotInnerEventDao
import com.it.curated.compose.database.dao.MarmotKeyPackageBundleDao
import com.it.curated.compose.database.dao.MarmotKeyPackageDao
import com.it.curated.compose.database.dao.MarmotRetainedEpochSecretDao
import com.it.curated.compose.database.dao.MentionDao
import com.it.curated.compose.database.dao.NegentropySynchronizeRequestDao
import com.it.curated.compose.database.dao.NegentropySynchronizeResultDao
import com.it.curated.compose.database.dao.NostrDao
import com.it.curated.compose.database.dao.NostrEventDao
import com.it.curated.compose.database.dao.NostrEventRelayDao
import com.it.curated.compose.database.dao.NostrNip17Dao
import com.it.curated.compose.database.dao.ParticipantDao
import com.it.curated.compose.database.dao.PostDao
import com.it.curated.compose.database.dao.ProfileDao
import com.it.curated.compose.database.dao.QuotedRelationDao
import com.it.curated.compose.database.dao.ReactionDao
import com.it.curated.compose.database.dao.RecentSearchDao
import com.it.curated.compose.database.dao.RelayDao
import com.it.curated.compose.database.dao.RepostedRelationDao
import com.it.curated.compose.database.dao.SynchronizeNostrEventRequestDao
import com.it.curated.compose.database.dao.SynchronizeNostrEventResultDao
import com.it.curated.compose.database.dao.UnsignedNostrEventDao
import com.it.curated.compose.database.dao.ZapDao
import com.it.curated.compose.database.model.BroadcastNostrEventReceipt
import com.it.curated.compose.database.model.BroadcastNostrEventRequest
import com.it.curated.compose.database.model.ChatMessage
import com.it.curated.compose.database.model.ChatMessageBroadcastNostrEventReceiptRelation
import com.it.curated.compose.database.model.ChatMessageBroadcastNostrEventRequestRelation
import com.it.curated.compose.database.model.ChatMessageNostrEventRelation
import com.it.curated.compose.database.model.ChatRoom
import com.it.curated.compose.database.model.Connection
import com.it.curated.compose.database.model.GiftWrapMessage
import com.it.curated.compose.database.model.GiftWrapPayload
import com.it.curated.compose.database.model.GiftWrapSeal
import com.it.curated.compose.database.model.GroupKeyState
import com.it.curated.compose.database.model.GroupSignedEvent
import com.it.curated.compose.database.model.InReplyToRelation
import com.it.curated.compose.database.model.MantraArtifact
import com.it.curated.compose.database.model.MantraArtifactVersion
import com.it.curated.compose.database.model.MantraChapter
import com.it.curated.compose.database.model.MantraChunk
import com.it.curated.compose.database.model.MantraDialect
import com.it.curated.compose.database.model.MantraTranslation
import com.it.curated.compose.database.model.MantraTranslationArtifactVersion
import com.it.curated.compose.database.model.MantraTranslationArtifactVersionContributor
import com.it.curated.compose.database.model.MantraTranslationChapter
import com.it.curated.compose.database.model.MantraTranslationChapterContributor
import com.it.curated.compose.database.model.MantraTranslationChunk
import com.it.curated.compose.database.model.MantraTranslationContributor
import com.it.curated.compose.database.model.MarmotCommitResult
import com.it.curated.compose.database.model.MarmotGroupEvent
import com.it.curated.compose.database.model.MarmotInnerEvent
import com.it.curated.compose.database.model.MarmotKeyPackage
import com.it.curated.compose.database.model.MarmotKeyPackageBundle
import com.it.curated.compose.database.model.MarmotRetainedEpochSecret
import com.it.curated.compose.database.model.Mention
import com.it.curated.compose.database.model.NegentropySynchronizeRequest
import com.it.curated.compose.database.model.NegentropySynchronizeResult
import com.it.curated.compose.database.model.NostrEvent
import com.it.curated.compose.database.model.NostrEventRelay
import com.it.curated.compose.database.model.Participant
import com.it.curated.compose.database.model.Post
import com.it.curated.compose.database.model.Profile
import com.it.curated.compose.database.model.QuotedRelation
import com.it.curated.compose.database.model.Reaction
import com.it.curated.compose.database.model.RecentSearch
import com.it.curated.compose.database.model.DkgParticipantMessage
import com.it.curated.compose.database.model.DkgSession
import com.it.curated.compose.database.model.FrostSignerMessage
import com.it.curated.compose.database.model.FrostSigningItem
import com.it.curated.compose.database.model.FrostSigningSession
import com.it.curated.compose.database.model.Relay
import com.it.curated.compose.database.model.RepostedRelation
import com.it.curated.compose.database.model.SynchronizeNostrEventRequest
import com.it.curated.compose.database.model.SynchronizeNostrEventResult
import com.it.curated.compose.database.model.UnsignedNostrEvent
import com.it.curated.compose.database.model.Zap
import kotlin.time.Instant

val GENESIS_AT = Instant.fromEpochMilliseconds(1231006505000L)

@Database(
    entities = [
        BroadcastNostrEventReceipt::class,
        BroadcastNostrEventRequest::class,
        Connection::class,
        ChatMessage::class,
        ChatMessageBroadcastNostrEventRequestRelation::class,
        ChatMessageBroadcastNostrEventReceiptRelation::class,
        ChatMessageNostrEventRelation::class,
        ChatRoom::class,
        DkgParticipantMessage::class,
        DkgSession::class,
        FrostSignerMessage::class,
        FrostSigningItem::class,
        FrostSigningSession::class,
        GiftWrapMessage::class,
        GiftWrapSeal::class,
        GiftWrapPayload::class,
        GroupKeyState::class,
        GroupSignedEvent::class,
        InReplyToRelation::class,
        MantraArtifact::class,
        MantraArtifactVersion::class,
        MantraChapter::class,
        MantraChunk::class,
        MantraDialect::class,
        MantraTranslation::class,
        MantraTranslationArtifactVersion::class,
        MantraTranslationArtifactVersionContributor::class,
        MantraTranslationChapter::class,
        MantraTranslationChapterContributor::class,
        MantraTranslationChunk::class,
        MantraTranslationContributor::class,
        MarmotCommitResult::class,
        MarmotGroupEvent::class,
        MarmotInnerEvent::class,
        MarmotKeyPackage::class,
        MarmotKeyPackageBundle::class,
        MarmotRetainedEpochSecret::class,
        Mention::class,
        NegentropySynchronizeRequest::class,
        NegentropySynchronizeResult::class,
        NostrEvent::class,
        NostrEventRelay::class,
        Participant::class,
        Post::class,
        Profile::class,
        QuotedRelation::class,
        Reaction::class,
        RecentSearch::class,
        Relay::class,
        RepostedRelation::class,
        SynchronizeNostrEventRequest::class,
        SynchronizeNostrEventResult::class,
        UnsignedNostrEvent::class,
        Zap::class
    ],
    version = 19,
    autoMigrations = [
        // v2 only adds the DkgSession/DkgParticipantMessage tables, so Room can
        // generate the migration itself — nothing existing changes shape.
        AutoMigration(from = 1, to = 2),
        // v3 adds the three nullable approval timestamps to DkgSession. Nullable
        // additions need no default and drop no data, so Room generates this one
        // too. Rituals already in flight come back with all three null, which reads
        // as "not approved yet" and simply asks the member for each step.
        AutoMigration(from = 2, to = 3),
        // v4 changes no schema at all -- it rewrites `dkgApprovalNeeded` chat rows
        // into one type per ritual step. Data, not shape, so it is a manual
        // migration passed to the builder rather than an entry here. See
        // MIGRATION_3_4.
        //
        // v5 adds the nullable MarmotInnerEvent.payloadEventId, which names the
        // nip30303 event a SubmissionEvent rumor carries. Rumors queued before
        // this come back null, which reads as "not a submission" -- correct,
        // since none of them were.
        AutoMigration(from = 4, to = 5),
        // v6 adds the FrostSigningSession/FrostSignerMessage tables and the
        // nullable DkgSession.publicShares. New tables and a nullable column are
        // both shapes Room can migrate itself. A ceremony that completed before
        // this reads back null, and signing falls back to not cross-checking
        // shares rather than refusing to run.
        AutoMigration(from = 5, to = 6),
        // v7 adds the GroupKeyState table, which records what shared key a room
        // signs with instead of leaving it to be rederived. A new table is a
        // shape Room migrates itself. Rooms created before this have no row and
        // fall back to the rederivation scan in FrostSigningManager.completedKey,
        // which is why that scan stays.
        AutoMigration(from = 6, to = 7),
        // v8 adds a nullable direct message recipient to MarmotInnerEvent and
        // ChatMessage. Nullable additions need no default and drop no data, so Room
        // generates this one. Rows written before it come back null, which reads as
        // "not a direct message" -- the only answer that is true of all of them.
        AutoMigration(from = 7, to = 8),
        // v9 adds the nullable FrostSigningSession.derivationPath, which says
        // what key off the ceremony's the session signs as. Sessions started
        // before it read back null, meaning the untweaked threshold key -- which
        // is what they were signing as, so one caught mid-flight finishes the way
        // it began rather than switching keys between two of its own rounds.
        AutoMigration(from = 8, to = 9),
        // v10 moves FrostSigningSession's five per-event columns onto the new
        // FrostSigningItem table, so one session can sign a batch. It copies
        // before it drops, which no AutoMigration can express -- see
        // MIGRATION_9_10, and the note there on why an in-flight session losing
        // its nonce seed would be worse than losing the session.
        //
        // v11 adds the nullable ChatMessage.frostSigningSessionId, which says
        // which signing session a transcript line is about. Lines written before
        // it read back null and are read by the clock, the way they always were:
        // a room that could only propose one thing at a time cannot have written
        // two sessions' lines into the same stretch of transcript.
        AutoMigration(from = 10, to = 11),
        // v12 adds the nullable ChatRoom.archiveRequestedAt -- renamed to
        // chronicleRequestedAt in v14 -- which stops a device with no signed work
        // in a room asking the group for its history on every launch while an
        // answer is in flight. Rooms written before it read back null, meaning
        // "never asked" -- true of all of them, and harmless: the request only
        // goes out for a room that holds no signed work, and a room that holds
        // some will not ask.
        AutoMigration(from = 11, to = 12),
        // v13 adds the GroupSignedEvent table and the nullable
        // `groupSignedEventId` on every row a signed event turns into. A new
        // table and nullable additions are both shapes Room migrates itself.
        // Rows written before it read back null, meaning "this device does not
        // hold the event behind this row" -- true of all of them, since nothing
        // kept it. They are not backfilled: the events are gone, and inventing
        // an id for one would point a row at a signature nobody can produce.
        AutoMigration(from = 12, to = 13),
        // v14 changes one column name and three stored strings, all of which say
        // "archive" about the group's signed record. The word was wanted back for
        // the ordinary thing a user does to a chat, and a column called
        // `archiveRequestedAt` sitting on ChatRoom is exactly where the two
        // meanings would have met. Room can rename a column and cannot rewrite the
        // rows in the same breath, so this is a manual migration passed to the
        // builder rather than an entry here. See MIGRATION_13_14.
        //
        // v15 adds the nullable ChatRoom.joinedGroupAt, which says when this
        // device became a member and so which of the group's messages were never
        // its to read. Adding a nullable column is a shape Room migrates itself;
        // deleting the placeholder chat lines already written for those messages
        // is not, and a member who joined a busy room is looking at a screenful of
        // them. Manual for that half, so it too is passed to the builder rather
        // than listed here. See MIGRATION_14_15.
        //
        // v16 adds an index on ChatMessage.chatRoomId. No column changes and no
        // rows move -- the chat list now looks up each room's newest line, and
        // without the index that lookup reads every message on the device. Room
        // creates an index on its own.
        AutoMigration(from = 15, to = 16),
        // v17 adds four nullable columns for subgroups -- a group made by
        // another group, which certifies it with its own quorum. See
        // docs/subgroups.md.
        //
        // GroupKeyState gains `parentChatRoomId` and `birthCertificateJson`, the
        // parent's signature over this room's id and the index into it. ChatRoom
        // gains `parentChatRoomId`, a copy of the same verified value so the room
        // list needs no second read. DkgSession gains one too, which is an
        // unverified claim off a ceremony proposal and is read for nothing but a
        // screen's title.
        //
        // **None of the three is a foreign key.** A self-referential cascade on
        // ChatRoom would mean deleting a parent room deletes its subgroups and,
        // by their own cascades, those rooms' messages, participants, key states
        // and signed events. Each column also routinely names a room this device
        // does not have, since a subgroup member need never have been in the
        // parent, so a dangling value is the normal state. Adding nullable
        // columns is a shape Room migrates itself.
        AutoMigration(from = 16, to = 17),
        // v18 adds the nullable ChatMessage.dkgSessionId, the pair to
        // frostSigningSessionId, because "the room's ceremony" stopped naming one
        // thing. A subgroup whose admins are the whole group runs in the room the
        // group's own ceremony ran in, so a transcript line has to say which of
        // the two it is about -- otherwise a member tapping "your approval is
        // needed" is shown whichever ceremony is newest, which is routinely the
        // wrong one. Rows written before it read back null and fall back to the
        // clock, which is correct for them: nothing that predates subgroups ran
        // two ceremonies in one room.
        AutoMigration(from = 17, to = 18),
        // v19 adds two nullable DkgSession columns, both of which the ceremony's
        // room used to answer and no longer can: a subgroup's ChillDKG now runs
        // in the **parent's** Marmot room rather than in a NIP-17 room derived
        // from the child's admins. See docs/subgroups.md.
        //
        // `participantPublicKeys` is who the ceremony is with -- a subset of the
        // parent's members, so the room's roster is the wrong set -- and
        // `subject` is what the room it will make is to be called, which used to
        // be the ceremony room's own name. Both are read off the proposal, which
        // has carried both since before this move; what changed is where they
        // land. Rows written before this read back null and fall back to the
        // room, which is correct for them: a ceremony held in its own NIP-17 room
        // ran over exactly that room's members and was named after it.
        AutoMigration(from = 18, to = 19),
    ]
)
@ColumnTypeConverters(CuratedConverters::class)
abstract class CuratedDatabase: RoomDatabase() {
    abstract fun broadcastNostrEventReceiptDao(): BroadcastNostrEventReceiptDao
    abstract fun broadcastNostrEventRequestDao(): BroadcastNostrEventRequestDao

    abstract fun chatMessageDao(): ChatMessageDao

    abstract fun chatMessageBroadcastNostrEventRequestRelationDao(): ChatMessageBroadcastNostrEventRequestRelationDao

    abstract fun chatMessageBroadcastNostrEventReceiptRelationDao(): ChatMessageBroadcastNostrEventReceiptRelationDao

    abstract fun chatMessageNostrEventRelationDao(): ChatMessageNostrEventRelationDao

    abstract fun chatRoomDao(): ChatRoomDao

    abstract fun dkgSessionDao(): DkgSessionDao

    abstract fun frostSigningSessionDao(): FrostSigningSessionDao

    abstract fun groupKeyStateDao(): GroupKeyStateDao

    abstract fun groupSignedEventDao(): GroupSignedEventDao

    abstract fun connectionDao(): ConnectionDao

    abstract fun giftWrapMessageDao(): GiftWrapMessageDao

    abstract fun giftWrapSealDao(): GiftWrapSealDao

    abstract fun giftWrapPayloadDao(): GiftWrapPayloadDao

    abstract fun inReplyToRelationDao(): InReplyToRelationDao

    abstract fun mantraDao(): MantraDao

    abstract fun mantraArtifactDao(): MantraArtifactDao

    abstract fun mantraArtifactVersionDao(): MantraArtifactVersionDao

    abstract fun mantraChapterDao(): MantraChapterDao

    abstract fun mantraChunkDao(): MantraChunkDao

    abstract fun mantraDialectDao(): MantraDialectDao

    abstract fun mantraTranslationArtifactVersionDao(): MantraTranslationArtifactVersionDao

    abstract fun mantraTranslationArtifactVersionContributorDao(): MantraTranslationArtifactVersionContributorDao

    abstract fun mantraTranslationChapterDao(): MantraTranslationChapterDao

    abstract fun mantraTranslationChapterContributorDao(): MantraTranslationChapterContributorDao

    abstract fun mantraTranslationChunkDao(): MantraTranslationChunkDao

    abstract fun mantraTranslationDao(): MantraTranslationDao

    abstract fun mantraTranslationContributorDao(): MantraTranslationContributorDao

    abstract fun marmotCommitResultDao(): MarmotCommitResultDao

    abstract fun marmotGroupEventDao(): MarmotGroupEventDao

    abstract fun marmotInnerEventDao(): MarmotInnerEventDao

    abstract fun marmotKeyPackageBundleDao(): MarmotKeyPackageBundleDao

    abstract fun marmotKeyPackageDao(): MarmotKeyPackageDao

    abstract fun marmotOutboundDao(): MarmotOutboundDao

    abstract fun marmotRetainedEpochSecretDao(): MarmotRetainedEpochSecretDao

    abstract fun mentionDao(): MentionDao

    abstract fun negentropySynchronizeRequestDao(): NegentropySynchronizeRequestDao

    abstract fun negentropySynchronizeResultDao(): NegentropySynchronizeResultDao

    abstract fun nostrDao(): NostrDao

    abstract fun nostrEventDao(): NostrEventDao

    abstract fun nostrEventRelayDao(): NostrEventRelayDao

    abstract fun nostrNip17Dao(): NostrNip17Dao

    abstract fun participantDao(): ParticipantDao

    abstract fun postDao(): PostDao

    abstract fun profileDao(): ProfileDao

    abstract fun quotedRelationDao(): QuotedRelationDao

    abstract fun reactionDao(): ReactionDao

    abstract fun recentSearchDao(): RecentSearchDao

    abstract fun relayDao(): RelayDao

    abstract fun repostedRelationDao(): RepostedRelationDao

    abstract fun synchronizeNostrEventRequestDao(): SynchronizeNostrEventRequestDao

    abstract fun synchronizeNostrEventResultDao(): SynchronizeNostrEventResultDao

    abstract fun unsignedNostrEventDao(): UnsignedNostrEventDao
    abstract fun zapDao(): ZapDao

    suspend fun wipeData() {
        useWriterConnection { transactor ->
            transactor.immediateTransaction {
                // TODO: Actually delete the data...
            }
        }
    }
}