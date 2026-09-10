package to.curare.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.PrimaryKey
import to.curare.compose.database.model.traits.BroadcastableEntity
import to.curare.compose.database.model.traits.LocalStoreEntity
import to.curare.compose.database.model.traits.SoftDeletableEntity
import to.curare.compose.database.model.traits.TimestampedEntity
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["publicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ]
)
data class MarmotKeyPackage(
    @PrimaryKey
    val id: HexKey,

    val publicKey: HexKey,

    val tlsEncodedMarmotKeyPackage: ByteArray,

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): TimestampedEntity, LocalStoreEntity, BroadcastableEntity, SoftDeletableEntity {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MarmotKeyPackage

        if (id != other.id) return false
        if (publicKey != other.publicKey) return false
        if (!tlsEncodedMarmotKeyPackage.contentEquals(other.tlsEncodedMarmotKeyPackage)) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (savedAt != other.savedAt) return false
        if (deletedAt != other.deletedAt) return false
        if (broadcastedAt != other.broadcastedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + tlsEncodedMarmotKeyPackage.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + savedAt.hashCode()
        result = 31 * result + (deletedAt?.hashCode() ?: 0)
        result = 31 * result + (broadcastedAt?.hashCode() ?: 0)
        return result
    }

}