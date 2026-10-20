package com.machankura.bennu.compose.extensions

import com.vitorpamplona.quartz.marmot.mls.group.MlsGroup

fun MlsGroup.exporterSecret() = this.exporterSecret(
    "marmot",
    "group-event".encodeToByteArray(),
    32,
)