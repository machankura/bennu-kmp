package to.curare.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.marmot.GroupEventResult
import com.vitorpamplona.quartz.marmot.MarmotInboundProcessor
import com.vitorpamplona.quartz.marmot.MarmotOutboundProcessor
import com.vitorpamplona.quartz.marmot.appComponents.CurrentProfileGroupFactory
import com.vitorpamplona.quartz.marmot.appComponents.GroupProfileV1
import com.vitorpamplona.quartz.marmot.foundation.appEvents.MarmotAppEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.vitorpamplona.quartz.marmot.mip03GroupMessages.GroupEvent
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupManager
import com.vitorpamplona.quartz.marmot.mls.group.MlsGroupStateStore
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealedRumorEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.vitorpamplona.quartz.nipC7Chats.ChatEvent
import com.vitorpamplona.quartz.utils.RandomInstance
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import to.curare.compose.database.CurareDatabase
import to.curare.compose.database.builder.getRoomDatabase
import to.curare.compose.database.model.MarmotInnerEvent
import to.curare.compose.database.model.NostrEvent
import to.curare.compose.database.model.Profile
import to.curare.compose.database.repository.DatabaseMarmotRepository
import to.curare.compose.nostr.MarmotProfile
import to.curare.compose.nostr.Relays
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Two implementations in one current-profile group: the app's engine over a
 * `CurareDatabase`, and a bare library stand-in -- `MlsGroupManager` and the two
 * processors over an in-memory store -- playing Amethyst. docs/marmot-v2.md,
 * Phases 5 and 8.
 *
 * The stand-in makes the group and invites the app; the Welcome arrives as a NIP-59
 * gift wrap through the sync funnel; then messages cross in both directions, a
 * commit from the other side moves the app's room, and a second engine over the
 * same database -- a restart -- reads a message from the epoch before it.
 */
class CurrentProfileInteropJvmTest {

    private val db: CurareDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<CurareDatabase>()
    )
    private val marmotRepository = DatabaseMarmotRepository(db, CoroutineScope(Dispatchers.Default))

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair()
    private val userHex = user.pubKey.toHexKey()
    private val relay = "wss://relay.example"

    /** Amethyst: quartz's own managers over a store that forgets nothing while the test runs. */
    private class StandIn(val keyPair: KeyPair) {
        val hex = keyPair.pubKey.toHexKey()
        val signer = NostrSignerInternal(keyPair)
        private val store = object : MlsGroupStateStore {
            private val states = mutableMapOf<String, ByteArray>()
            private val retained = mutableMapOf<String, List<ByteArray>>()
            override suspend fun save(nostrGroupId: String, state: ByteArray) { states[nostrGroupId] = state }
            override suspend fun load(nostrGroupId: String): ByteArray? = states[nostrGroupId]
            override suspend fun delete(nostrGroupId: String) { states.remove(nostrGroupId); retained.remove(nostrGroupId) }
            override suspend fun listGroups(): List<String> = states.keys.toList()
            override suspend fun saveRetainedEpochs(nostrGroupId: String, retainedSecrets: List<ByteArray>) { retained[nostrGroupId] = retainedSecrets }
            override suspend fun loadRetainedEpochs(nostrGroupId: String): List<ByteArray> = retained[nostrGroupId].orEmpty()
        }
        val manager = MlsGroupManager(store)
        val inbound = MarmotInboundProcessor(manager, KeyPackageRotationManager())
        val outbound = MarmotOutboundProcessor(manager)
    }

    private val amethyst = StandIn(KeyPair())

    private suspend fun seedProfile(publicKey: String, name: String) {
        val eventId = publicKey.take(32).padEnd(64, 'e')
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = publicKey, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = publicKey, userName = name, nostrEventId = eventId))
    }

    private suspend fun store(event: Event) = db.nostrDao().storeNostrEvent(
        nostrEvent = NostrEvent(
            id = event.id, pubKey = event.pubKey, kind = event.kind, tags = event.tags,
            content = event.content, sig = event.sig, createdAt = Instant.fromEpochSeconds(event.createdAt),
        ),
        relayURL = relay,
        synchronizationRelayURLs = listOf(relay),
        level = 0,
        activeKeyPair = user,
    )

    /** The app's own published current-profile package, signed as the notary would. */
    private suspend fun ourKeyPackageEvent(): KeyPackageEvent {
        marmotRepository.publishMarmotKeyPackageBundle(user, setOf(MarmotProfile.CURRENT))
        val unsigned = db.unsignedNostrEventDao().getAllUnprocessedUnsignedNostrEvents()
            .single { it.kind == KeyPackageEvent.KIND && it.pubKey == userHex }
        return NostrSignerSync(user).signNormal(
            createdAt = unsigned.createdAt.epochSeconds, kind = unsigned.kind, tags = unsigned.tags, content = unsigned.content,
        )
    }

    /** The stand-in makes its group, adds us, and we join from the gift-wrapped Welcome. */
    private suspend fun joinedRoom(): String {
        seedProfile(userHex, "us")
        val ourEvent = ourKeyPackageEvent()
        val ourKeyPackage = KeyPackageUtils.decodeKeyPackage(Base64.decode(ourEvent.content))

        val routingId = RandomInstance.bytes(32)
        val routingHex = routingId.toHexKey()
        val group = CurrentProfileGroupFactory.createGroup(
            signer = amethyst.signer,
            nostrGroupId = routingId,
            relays = Relays.DefaultDMRelayList.map { it.url },
            profile = GroupProfileV1(name = "interop", description = ""),
        )
        amethyst.manager.adoptGroup(routingHex, group)
        amethyst.inbound.trackGroup(routingHex)

        val commit = amethyst.manager.addMember(routingHex, ourKeyPackage.toTlsBytes())
        val welcomeTemplate = WelcomeEvent.build(
            welcomeBase64 = Base64.encode(assertNotNull(commit.welcomeBytes)),
            keyPackageEventId = ourEvent.id,
            relays = Relays.DefaultDMRelayList,
            nostrGroupId = routingHex,
        )
        val inviterSigner = NostrSignerSync(amethyst.keyPair)
        val rumor = RumorAssembler.assembleRumor<WelcomeEvent>(pubKey = amethyst.hex, ev = welcomeTemplate)
        val seal = inviterSigner.signNormal<SealedRumorEvent>(
            createdAt = rumor.createdAt, kind = SealedRumorEvent.KIND, tags = emptyArray(),
            content = inviterSigner.nip44Encrypt(rumor.toJson(), userHex),
        )
        store(GiftWrapEvent.create(event = seal, recipientPubKey = userHex, createdAt = rumor.createdAt))

        assertNotNull(db.chatRoomDao().findChatRoomById(routingHex), "the app did not join")
        return routingHex
    }

    private suspend fun theirMessage(routingHex: String, text: String): GroupEvent =
        amethyst.outbound.buildAppEvent(
            routingHex,
            MarmotAppEvent.build(pubKey = amethyst.hex, kind = ChatEvent.KIND, content = text, createdAt = TimeUtils.now()),
        ).signedEvent

    private suspend fun transcript(roomId: String) =
        db.chatMessageDao().getChatMessagesByChatRoomId(roomId).map { it.chatMessage }

    @Test
    fun `a message from the other implementation is read and attributed to its sender`() = runBlocking {
        val roomId = joinedRoom()

        store(theirMessage(roomId, "hello from the other side"))

        val line = assertNotNull(transcript(roomId).firstOrNull { it.content == "hello from the other side" }, "the message was not filed")
        assertEquals(amethyst.hex, line.senderPublicKey)
        assertFalse(line.isUserMessage)
    }

    @Test
    fun `a message the app sends is read by the other implementation, as a canonical payload`() = runBlocking {
        val roomId = joinedRoom()
        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        val inner = MarmotInnerEvent(
            id = "7".repeat(64), publicKey = userHex, chatRoomId = roomId, kind = ChatEvent.KIND,
            tags = emptyArray(), content = "hi back",
        )

        db.marmotOutboundDao().encryptAndSendMarmotInnerEvent(
            localChatRoom = room,
            mlsGroup = assertNotNull(room.chatRoom.toMlsGroup()),
            marmotInnerEvent = inner,
            nostrSignerSync = NostrSignerSync(user),
        )

        val sent = db.nostrEventDao().getMLSGroupEvents(since = Instant.fromEpochSeconds(0), limit = 10)
            .mapNotNull { it.toGroupEvent(userHex) }
            .single()
        val result = assertIs<GroupEventResult.ApplicationMessage>(amethyst.inbound.processGroupEvent(sent))
        val payload = assertNotNull(MarmotAppEvent.decodeOrNull(result.innerEventJson.replace(",\"sig\":\"\"}", "}")), "not a canonical payload")
        assertEquals("hi back", payload.content)
        assertEquals(userHex, payload.pubKey)
    }

    /**
     * A commit from the other side moves the app's room to the same epoch, adds the
     * member it added, and is persisted by the engine's manager -- the state on the
     * row is the manager's, not a stale instance's.
     */
    @Test
    fun `a commit from the other implementation moves the room and its membership`() = runBlocking {
        val roomId = joinedRoom()
        val third = KeyPair()
        seedProfile(third.pubKey.toHexKey(), "third")
        val thirdPackage = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(third)).keyPackage

        val commit = amethyst.manager.addMember(roomId, thirdPackage.toTlsBytes())
        val commitEvent = amethyst.outbound.buildCommitEvent(roomId, commit.framedCommitBytes, commit.preCommitExporterSecret).signedEvent
        store(commitEvent)

        val room = assertNotNull(db.chatRoomDao().findChatRoomById(roomId))
        val restored = assertNotNull(room.chatRoom.toMlsGroup())
        assertEquals(amethyst.manager.getGroup(roomId)!!.epoch, restored.epoch, "the app's persisted epoch lags the group's")
        assertTrue(restored.currentMemberIdentities().contains(third.pubKey.toHexKey()))
        assertTrue(room.localParticipants.any { it.participant.participantPublicKey == third.pubKey.toHexKey() }, "the new member has no row")
        // And the epoch left behind is on disk, not in a map.
        assertTrue(db.marmotRetainedEpochSecretDao().getMarmotRetainedEpochSecretForChatRoomId(roomId).isNotEmpty())
    }

    /**
     * The case the old pipeline could not pass: a message from the epoch before a
     * commit, read by an engine rebuilt over the same database -- the retained epochs
     * come back from the store rather than from a map that died with the process.
     */
    @Test
    fun `a message from the prior epoch decrypts after the engine is rebuilt`() = runBlocking {
        val roomId = joinedRoom()
        val late = theirMessage(roomId, "sent before the commit")

        val third = CurrentProfileGroupFactory.createKeyPackage(NostrSignerInternal(KeyPair())).keyPackage
        val commit = amethyst.manager.addMember(roomId, third.toTlsBytes())
        store(amethyst.outbound.buildCommitEvent(roomId, commit.framedCommitBytes, commit.preCommitExporterSecret).signedEvent)

        val rebuilt = MarmotEngine(db)
        assertNotNull(rebuilt.groupFor(roomId), "the rebuilt engine does not restore the room")
        val result = assertIs<GroupEventResult.ApplicationMessage>(rebuilt.inbound.processGroupEvent(late))
        assertTrue(result.innerEventJson.contains("sent before the commit"))
    }
}
