package com.it.curated.compose.database.dao

import com.vitorpamplona.quartz.marmot.mls.crypto.Ed25519
import com.vitorpamplona.quartz.marmot.mls.crypto.MlsCryptoProvider
import com.vitorpamplona.quartz.marmot.mls.crypto.X25519
import com.vitorpamplona.quartz.marmot.mls.messages.MlsKeyPackage
import com.vitorpamplona.quartz.marmot.mls.tree.Capabilities
import com.vitorpamplona.quartz.marmot.mls.tree.Credential
import com.vitorpamplona.quartz.marmot.mls.tree.Extension
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNode
import com.vitorpamplona.quartz.marmot.mls.tree.LeafNodeSource
import com.vitorpamplona.quartz.marmot.mls.tree.Lifetime
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.utils.TimeUtils
import com.it.curated.compose.database.model.MarmotKeyPackage

/**
 * A real MLS key package for [publicKey], built the same way
 * `DatabaseMarmotRepository.generateKeyPackage` builds this device's own. Entirely local:
 * three key generations, a signed leaf node and a signed key package. No relay, no network,
 * nothing to stub.
 */
internal fun marmotKeyPackageFor(publicKey: HexKey): MarmotKeyPackage {
    val initKp = X25519.generateKeyPair()
    val encKp = X25519.generateKeyPair()
    val sigKp = Ed25519.generateKeyPair()
    val now = TimeUtils.now()

    val unsignedLeaf = LeafNode(
        encryptionKey = encKp.publicKey,
        signatureKey = sigKp.publicKey,
        credential = Credential.Basic(publicKey.hexToByteArray()),
        capabilities = Capabilities(
            // LastResort, then NostrGroupData -- the group's RequiredCapabilities rejects a
            // leaf that does not advertise both, so a fixture without them is refused at
            // addMember rather than at decode.
            extensions = listOf(0x000A, 0xF2EE),
            proposals = listOf(0x000A),
        ),
        leafNodeSource = LeafNodeSource.KEY_PACKAGE,
        lifetime = Lifetime(notBefore = now, notAfter = now + 60L * 60L * 24L * 90L),
        extensions = emptyList(),
        signature = ByteArray(0),
    )
    val leafNode = unsignedLeaf.copy(
        signature = MlsCryptoProvider.signWithLabel(
            sigKp.privateKey,
            "LeafNodeTBS",
            unsignedLeaf.encodeTbs(groupId = null, leafIndex = null),
        ),
    )

    val unsigned = MlsKeyPackage(
        initKey = initKp.publicKey,
        leafNode = leafNode,
        extensions = listOf(Extension(extensionType = 0x000A, extensionData = ByteArray(0))),
        signature = ByteArray(0),
    )
    val keyPackage = unsigned.copy(
        signature = MlsCryptoProvider.signWithLabel(
            sigKp.privateKey,
            "KeyPackageTBS",
            unsigned.encodeTbs(),
        ),
    )

    return MarmotKeyPackage(
        id = publicKey,
        publicKey = publicKey,
        tlsEncodedMarmotKeyPackage = keyPackage.toTlsBytes(),
    )
}
