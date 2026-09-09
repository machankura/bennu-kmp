package com.it.curated.compose.database.repository

import com.it.curated.compose.database.CuratedDatabase
import com.it.curated.compose.database.model.MarmotInnerEvent
import com.it.curated.compose.database.model.MarmotKeyPackageBundle
import com.it.curated.compose.database.model.UnsignedNostrEvent
import com.it.curated.compose.exceptions.MarmotMissingChatGroupException
import com.it.curated.compose.extensions.toHex
import com.it.curated.compose.nostr.Relays
import com.it.curated.compose.repository.MarmotRepository
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageEvent
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageRotationManager.Companion.KEY_PACKAGE_LIFETIME_SECONDS
import com.vitorpamplona.quartz.marmot.mip00KeyPackages.KeyPackageUtils
import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.crypto.MlsCryptoProvider
import com.vitorpamplona.quartz.marmot.mls.crypto.X25519
import com.vitorpamplona.quartz.marmot.mls.messages.KeyPackageBundle
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.marmot.mls.tree.Capabilities
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.marmot.mls.tree.Extension
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNode
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNodeSource
import com.vitorpamplona.quartz.marmot.mls.tree.Lifetime
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * Based on MlsGroupManager in quarts...
 */
class DatabaseMarmotRepository(
    val database: CuratedDatabase,
    val scope: CoroutineScope
): MarmotRepository {
    private val logger = Logger.withTag(TAG)

    override suspend fun publishMarmotKeyPackageBundle(
        publicKey: HexKey,
        nsecPassword: String
    ) {
        logger.d("saveMarmotKeyPackageBundle: $publicKey")
        // KeyPackage...
        val dTag = KeyPackageUtils.generateRandomDTag() // TODO: Persist this...
        val identity = publicKey.hexToByteArray()
        val bundle = generateKeyPackage(identity, dTag)

        val keyPackageBytes = bundle.keyPackage.toTlsBytes()
        val keyPackageBase64 = Base64.encode(keyPackageBytes)
        val keyPackageRef = bundle.keyPackage.reference().toHexKey()

        val keyPackageTemplate = KeyPackageEvent.build(
            keyPackageBase64 = keyPackageBase64,
            dTagSlot = dTag,
            keyPackageRef = keyPackageRef,
            relays = Relays.DefaultDMRelayList,
        )

        database.unsignedNostrEventDao().upsert(
            UnsignedNostrEvent(
                pubKey = publicKey,
                kind = keyPackageTemplate.kind,
                tags = keyPackageTemplate.tags,
                createdAt = Instant.fromEpochSeconds(keyPackageTemplate.createdAt),
                content = keyPackageTemplate.content
            )
        )

        val id = EventHasher.hashId(
            pubKey = publicKey,
            createdAt = keyPackageTemplate.createdAt,
            kind = keyPackageTemplate.kind,
            tags = keyPackageTemplate.tags,
            content = keyPackageTemplate.content
        )
        logger.d("keyPackageEventId: $id")

        // ⚠️ SECURITY / KNOWN VULNERABILITY (H1: MLS private keys stored in
        //  plaintext at rest). Despite the `ncryptsec*` field names, the values
        //  below are RAW hex private keys (MLS init / encryption / signature).
        //  Any DB compromise — device backup, forensic extraction, another app
        //  on a rooted/jailbroken device — yields the group's decryption and
        //  signing keys, defeating E2E confidentiality and enabling
        //  impersonation. The same applies to `mlsGroupState` persisted in
        //  MarmotInboundManager.persistGroup (see the warning there).
        //  TODO(security): Encrypt these at rest with a platform-backed
        //   key-encryption-key (Android Keystore / iOS Keychain / a JVM secret
        //   store) — the intended ncryptsec scheme — before this ships.
        database.marmotKeyPackageBundleDao().upsert(
            MarmotKeyPackageBundle(
                id = id,
                publicKey = publicKey,
                tlsEncodedMarmotKeyPackage = bundle.keyPackage.toTlsBytes(),
                ncryptsecInitPrivateKey = bundle.initPrivateKey.toHex(), // TODO(security): encrypt at rest, do not store raw
                ncryptsecEncryptionPrivateKey = bundle.encryptionPrivateKey.toHex(), // TODO(security): encrypt at rest, do not store raw
                ncryptsecSignaturePrivateKey = bundle.signaturePrivateKey.toHex() // TODO(security): encrypt at rest, do not store raw
            )
        )
    }

    override suspend fun rotateMarmotKeyPackageBundle(marmotKeyPackageBundle: MarmotKeyPackageBundle) {
        database.marmotKeyPackageBundleDao().upsert(
            marmotKeyPackageBundle.copy(
                rotated = true
            )
        )
    }

    override suspend fun observeMarmotKeyPackageBundles(publicKey: HexKey): Flow<List<MarmotKeyPackageBundle>> {
        return database.marmotKeyPackageBundleDao().observeAllMarmotKeyPackageBundles(publicKey)
    }

    override suspend fun getMarmotKeyPackageBundles(publicKey: HexKey): List<MarmotKeyPackageBundle> {
        return database.marmotKeyPackageBundleDao().getAllMarmotKeyPackageBundles(publicKey)
    }

    override suspend fun observeUnprocessedMarmotInnerEvents(publicKey: HexKey): Flow<List<MarmotInnerEvent>> {
        return database.marmotInnerEventDao().observeUnprocessedMarmotInnerEvents(publicKey)
    }

    override suspend fun observeActiveMarmotKeyPackageBundle(publicKey: HexKey): Flow<MarmotKeyPackageBundle?> {
        return database.marmotKeyPackageBundleDao().observeActiveMarmotKeyPackageBundle(publicKey)
    }

    /**
     * Both lookups used to be `?.let`, so a row for a room this device holds no MLS state
     * for returned quietly, having done nothing. Nothing marks such a row, so it stayed
     * queued and was handed back on the next pass, forever -- and while the queue was a
     * single row, forever meant every message queued behind it too. Saying so is the same
     * call `MarmotOutboundDao.inviteMemberToChatRoom` makes, for the same reason: there is
     * no group to encrypt to, and reporting success is a lie the sender acts on.
     */
    override suspend fun encryptAndSendMarmotInnerEvent(
        marmotInnerEvent: MarmotInnerEvent,
        nostrSignerSync: NostrSignerSync
    ) {
        val localChatRoom = database.chatRoomDao().findChatRoomById(marmotInnerEvent.chatRoomId)
            ?: throw MarmotMissingChatGroupException(
                "Cannot send ${marmotInnerEvent.id}: no chat room ${marmotInnerEvent.chatRoomId}"
            )

        val mlsGroup = localChatRoom.chatRoom.toMlsGroup()
            ?: throw MarmotMissingChatGroupException(
                "Cannot send ${marmotInnerEvent.id}: ${marmotInnerEvent.chatRoomId} holds no MLS group state"
            )

        logger.d("mlsGroup: $mlsGroup")

        database.marmotOutboundDao().encryptAndSendMarmotInnerEvent(
            localChatRoom = localChatRoom,
            mlsGroup = mlsGroup,
            marmotInnerEvent = marmotInnerEvent,
            nostrSignerSync = nostrSignerSync
        )
    }

    private fun generateKeyPackage(
        identity: ByteArray,
        dTagSlot: String = KeyPackageUtils.PRIMARY_SLOT,
    ): KeyPackageBundle {
        val initKp = X25519.generateKeyPair()
        val encKp = X25519.generateKeyPair()
        val sigKp = Ed25519.generateKeyPair()

        val leafNode =
            buildKeyPackageLeafNode(
                encryptionKey = encKp.publicKey,
                signatureKey = sigKp.publicKey,
                identity = identity,
                signingKey = sigKp.privateKey,
            )

        val unsigned =
            MlsKeyPackage(
                initKey = initKp.publicKey,
                leafNode = leafNode,
                // LastResort (0x000A) marks this as a last-resort KeyPackage per MLS Extensions draft.
                // MDK always marks KeyPackages as last-resort via .mark_as_last_resort().
                extensions = listOf(
                    Extension(
                        extensionType = 0x000A,
                        extensionData = ByteArray(0)
                    )
                ),
                signature = ByteArray(0),
            )
        val keyPackage =
            unsigned.copy(
                signature =
                    MlsCryptoProvider.signWithLabel(
                        sigKp.privateKey,
                        "KeyPackageTBS",
                        unsigned.encodeTbs(),
                    ),
            )

        val bundle = KeyPackageBundle(
            keyPackage,
            initPrivateKey = initKp.privateKey,
            encryptionPrivateKey = encKp.privateKey,
            signaturePrivateKey = sigKp.privateKey
        )

        return bundle
    }


    private fun buildKeyPackageLeafNode(
        encryptionKey: ByteArray,
        signatureKey: ByteArray,
        identity: ByteArray,
        signingKey: ByteArray,
    ): LeafNode {
        val now = TimeUtils.now()
        val unsigned =
            LeafNode(
                encryptionKey = encryptionKey,
                signatureKey = signatureKey,
                credential = Credential.Basic(identity),
                capabilities =
                    Capabilities(
                        extensions =
                            listOf(
                                0x000A, // LastResort (required by OpenMLS validation)
                                0xF2EE, // NostrGroupData (required by group's RequiredCapabilities)
                            ),
                        proposals =
                            listOf(
                                0x000A, // SelfRemove (required by group's RequiredCapabilities)
                            ),
                    ),
                leafNodeSource = LeafNodeSource.KEY_PACKAGE,
                lifetime = Lifetime(
                    notBefore = now,
                    notAfter = now + KEY_PACKAGE_LIFETIME_SECONDS
                ),
                extensions = emptyList(),
                signature = ByteArray(0),
            )

        val tbs = unsigned.encodeTbs(groupId = null, leafIndex = null)
        val signature = MlsCryptoProvider.signWithLabel(signingKey, "LeafNodeTBS", tbs)
        return unsigned.copy(signature = signature)
    }

    companion object {
        private const val TAG = "DatabaseMarmotRepository"
    }
}