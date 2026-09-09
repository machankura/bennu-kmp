package com.it.curated.compose.database.model.traits

import com.vitorpamplona.quartz.nip01Core.core.HexKey

interface OptionalNostrEventEntity {
    val nostrEventId: HexKey?
}