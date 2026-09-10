package to.curare.compose.database.model

import androidx.room3.Entity
import androidx.room3.ForeignKey
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Clock
import kotlin.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["sourcePublicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["publicKey"],
            childColumns = ["destinationPublicKey"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["sourcePublicKey", "destinationPublicKey"]
)
data class Connection(
    val sourcePublicKey: HexKey,
    val destinationPublicKey: HexKey,

    override val nostrEventId: HexKey, // This is the contactListEvent with this following...

    override val createdAt: Instant = Clock.System.now(),
    override val updatedAt: Instant = Clock.System.now(),
    override val savedAt: Instant = Clock.System.now(),
    override val deletedAt: Instant? = null,
    override val broadcastedAt: Instant? = null,
): to.curare.compose.database.model.traits.NostrEventEntity,
    to.curare.compose.database.model.traits.TimestampedEntity,
    to.curare.compose.database.model.traits.LocalStoreEntity,
    to.curare.compose.database.model.traits.BroadcastableEntity,
    to.curare.compose.database.model.traits.SoftDeletableEntity
