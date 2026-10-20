package com.machankura.bennu.compose.repository

import kotlinx.coroutines.flow.Flow

interface RelayRepository {
    suspend fun observePublicKeyRelays(publicKey: String): Flow<List<com.machankura.bennu.compose.database.model.Relay>>
}