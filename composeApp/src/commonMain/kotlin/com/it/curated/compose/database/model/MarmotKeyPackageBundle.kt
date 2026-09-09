package com.it.curated.compose.database.model

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.it.curated.compose.database.model.traits.BroadcastableEntity
import com.it.curated.compose.database.model.traits.LocalStoreEntity
import com.it.curated.compose.database.model.traits.SoftDeletableEntity
import com.it.curated.compose.database.model.traits.TimestampedEntity
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
//    foreignKeys = [
//        ForeignKey(
//            entity = Profile::class,
//            parentColumns = ["publicKey"],
//            childColumns = ["publicKey"],
//            onDelete = ForeignKey.CASCADE,
//        ),
//    ],
    indices = [
        Index("publicKey"),
    ],
)
data class MarmotKeyPackageBundle(
    @PrimaryKey
    val id: HexKey,

    val publicKey: HexKey,

    val tlsEncodedMarmotKeyPackage: ByteArray,

    val ncryptsecInitPrivateKey: String,
    val ncryptsecEncryptionPrivateKey: String,
    val ncryptsecSignaturePrivateKey: String,

    val consumed: Boolean = false,
    val rotated: Boolean = false, // TODO: Should we have rotatedByID

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): TimestampedEntity, LocalStoreEntity, BroadcastableEntity, SoftDeletableEntity {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MarmotKeyPackageBundle

        if (consumed != other.consumed) return false
        if (rotated != other.rotated) return false
        if (id != other.id) return false
        if (publicKey != other.publicKey) return false
        if (!tlsEncodedMarmotKeyPackage.contentEquals(other.tlsEncodedMarmotKeyPackage)) return false
        if (ncryptsecInitPrivateKey != other.ncryptsecInitPrivateKey) return false
        if (ncryptsecEncryptionPrivateKey != other.ncryptsecEncryptionPrivateKey) return false
        if (ncryptsecSignaturePrivateKey != other.ncryptsecSignaturePrivateKey) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (deletedAt != other.deletedAt) return false
        if (broadcastedAt != other.broadcastedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = consumed.hashCode()
        result = 31 * result + rotated.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + tlsEncodedMarmotKeyPackage.contentHashCode()
        result = 31 * result + ncryptsecInitPrivateKey.hashCode()
        result = 31 * result + ncryptsecEncryptionPrivateKey.hashCode()
        result = 31 * result + ncryptsecSignaturePrivateKey.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + (broadcastedAt?.hashCode() ?: 0)
        return result
    }
}