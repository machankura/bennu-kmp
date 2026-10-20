package com.machankura.bennu.compose.database.model.traits

import com.vitorpamplona.quartz.nip01Core.core.HexKey

interface ReplyingToEntity {
    val replyToId: HexKey?
}