package to.curare.compose.database.repository

class DatabaseSearchRepository(
    private val database: to.curare.compose.database.CurareDatabase
): to.curare.compose.repository.SearchRepository {

    override suspend fun submitSearch(synchronizeNostrEventRequests: List<to.curare.compose.database.model.SynchronizeNostrEventRequest>) {
        database.synchronizeNostrEventRequestDao().insert(
            synchronizeNostrEventRequests = synchronizeNostrEventRequests
        )
    }
}