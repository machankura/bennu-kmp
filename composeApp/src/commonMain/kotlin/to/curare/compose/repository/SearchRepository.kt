package to.curare.compose.repository

interface SearchRepository {

    suspend fun submitSearch(
        synchronizeNostrEventRequests: List<to.curare.compose.database.model.SynchronizeNostrEventRequest>,
    )

    companion object {
        val NO_OP_SEARCH_REPOSITORY = object: SearchRepository {
            override suspend fun submitSearch(synchronizeNostrEventRequests: List<to.curare.compose.database.model.SynchronizeNostrEventRequest>) {
                TODO("Not yet implemented")
            }
        }
    }
}