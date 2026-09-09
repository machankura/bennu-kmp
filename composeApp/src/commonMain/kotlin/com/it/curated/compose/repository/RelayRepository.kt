package com.it.curated.compose.repository

import kotlinx.coroutines.flow.Flow

interface RelayRepository {
    suspend fun observePublicKeyRelays(publicKey: String): Flow<List<com.it.curated.compose.database.model.Relay>>
}