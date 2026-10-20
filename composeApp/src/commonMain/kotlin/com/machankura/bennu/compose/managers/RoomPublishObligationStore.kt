package com.machankura.bennu.compose.managers

import com.vitorpamplona.quartz.marmot.protocolCore.MarmotPublishObligationStore
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.bennu.compose.database.BennuDatabase
import com.machankura.bennu.compose.database.model.MarmotOutboundGate
import com.machankura.bennu.compose.database.model.MarmotPublishObligation

/**
 * The publish gate's durable half, over the app's tables. See
 * [com.machankura.bennu.compose.database.model.MarmotPublishObligation].
 */
class RoomPublishObligationStore(
    private val database: BennuDatabase,
) : MarmotPublishObligationStore {
    override suspend fun save(obligationId: HexKey, bytes: ByteArray) {
        database.marmotPublishObligationDao().upsert(MarmotPublishObligation(obligationId, bytes))
    }

    override suspend fun delete(obligationId: HexKey) {
        database.marmotPublishObligationDao().delete(obligationId)
    }

    override suspend fun loadAll(): List<ByteArray> =
        database.marmotPublishObligationDao().getAll().map { it.bytes }

    override suspend fun saveGate(groupId: HexKey, gate: String) {
        database.marmotPublishObligationDao().upsertGate(MarmotOutboundGate(groupId, gate))
    }

    override suspend fun deleteGate(groupId: HexKey) {
        database.marmotPublishObligationDao().deleteGate(groupId)
    }

    override suspend fun loadGates(): Map<HexKey, String> =
        database.marmotPublishObligationDao().getAllGates().associate { it.groupId to it.gate }
}
