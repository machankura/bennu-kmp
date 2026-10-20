package com.machankura.bennu.compose.database.repository

class DatabaseSearchRepository(
    private val database: com.machankura.bennu.compose.database.BennuDatabase
): com.machankura.bennu.compose.repository.SearchRepository {

    override suspend fun submitSearch(synchronizeNostrEventRequests: List<com.machankura.bennu.compose.database.model.SynchronizeNostrEventRequest>) {
        database.synchronizeNostrEventRequestDao().insert(
            synchronizeNostrEventRequests = synchronizeNostrEventRequests
        )
    }
}