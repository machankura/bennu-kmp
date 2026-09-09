package com.it.curated.compose.repository

interface SearchRepository {

    suspend fun submitSearch(
        synchronizeNostrEventRequests: List<com.it.curated.compose.database.model.SynchronizeNostrEventRequest>,
    )

    companion object {
        val NO_OP_SEARCH_REPOSITORY = object: SearchRepository {
            override suspend fun submitSearch(synchronizeNostrEventRequests: List<com.it.curated.compose.database.model.SynchronizeNostrEventRequest>) {
                TODO("Not yet implemented")
            }
        }
    }
}