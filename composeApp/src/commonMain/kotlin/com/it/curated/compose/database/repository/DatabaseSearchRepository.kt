package com.it.curated.compose.database.repository

class DatabaseSearchRepository(
    private val database: com.it.curated.compose.database.CuratedDatabase
): com.it.curated.compose.repository.SearchRepository {

    override suspend fun submitSearch(synchronizeNostrEventRequests: List<com.it.curated.compose.database.model.SynchronizeNostrEventRequest>) {
        database.synchronizeNostrEventRequestDao().insert(
            synchronizeNostrEventRequests = synchronizeNostrEventRequests
        )
    }
}