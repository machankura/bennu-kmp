package to.curare.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip02FollowList.ContactListEvent
import com.vitorpamplona.quartz.nip17Dm.settings.ChatMessageRelayListEvent
import com.vitorpamplona.quartz.nip28PublicChat.list.ChannelListEvent
import com.vitorpamplona.quartz.nip50Search.SearchRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.IndexerRelayListEvent
import com.vitorpamplona.quartz.nip51Lists.relayLists.RelayFeedsListEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UnqueuedProfileSynchronizationViewModel(
    val unsignedNostrEventId: Long,
    val profilePublicKey: String,
    val nostrRepository: to.curare.compose.repository.NostrRepository
): ViewModel() {
    companion object {
        private const val TAG = "UnqueuedProfileSynchronizationViewModel"

        fun factory(
            unsignedNostrEventId: Long,
            profilePublicKey: String,
            nostrRepository: to.curare.compose.repository.NostrRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                UnqueuedProfileSynchronizationViewModel(
                    unsignedNostrEventId = unsignedNostrEventId,
                    profilePublicKey = profilePublicKey,
                    nostrRepository = nostrRepository
                )
            }
        }
    }

    private val logger = Logger.withTag(TAG)

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun queueSynchronization() {
        logger.d { "queueSynchronization" }
        viewModelScope.launch(Dispatchers.IO) {

            delay(2_000)

            nostrRepository.queueSynchronizeNostrEvent(
                to.curare.compose.nostr.Relays.DefaultDMRelayList.map { normalizedRelayUrl ->
                    to.curare.compose.database.model.SynchronizeNostrEventRequest(
                        purpose = "sign-in",
                        synchronizationFilters = arrayOf(
                            to.curare.compose.database.model.types.SynchronizationFilter(
                                authors = arrayOf(profilePublicKey),
                                kinds = arrayOf(
                                    MetadataEvent.KIND,
                                    ContactListEvent.KIND,
                                    AdvertisedRelayListEvent.KIND,
                                    ChatMessageRelayListEvent.KIND,
                                    SearchRelayListEvent.KIND,
                                    IndexerRelayListEvent.KIND,
                                    ChannelListEvent.KIND,
                                    RelayFeedsListEvent.KIND
                                )
                            )
                        ),
                        unsignedNostrEventId = unsignedNostrEventId,
                        relayURL = normalizedRelayUrl.url,
                        level = 0
                    )
                }

            )
        }

    }
}