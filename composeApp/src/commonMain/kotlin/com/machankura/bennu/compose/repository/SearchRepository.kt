package com.machankura.bennu.compose.repository

interface SearchRepository {

    suspend fun submitSearch(
        synchronizeNostrEventRequests: List<com.machankura.bennu.compose.database.model.SynchronizeNostrEventRequest>,
    )

    companion object {
        val NO_OP_SEARCH_REPOSITORY = object: SearchRepository {
            override suspend fun submitSearch(synchronizeNostrEventRequests: List<com.machankura.bennu.compose.database.model.SynchronizeNostrEventRequest>) {
                TODO("Not yet implemented")
            }
        }
    }
}