package com.machankura.bennu.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.bennu.compose.repository.ChatRepository
import com.machankura.bennu.compose.ui.view.state.GroupWalletAddressesUIState
import com.machankura.bennu.compose.wallet.GroupOnchainWallets
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The group wallet's addresses, watched for as long as the screen is open.
 *
 * [wallets] hands back the wallet the wallet screen is already watching, so the
 * list costs no subscriptions of its own and agrees with that screen about which
 * address is current.
 */
class GroupWalletAddressesViewModel(
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupWalletAddressesUIState,
    val chatRepository: ChatRepository,
    private val wallets: GroupOnchainWallets?,
): ViewModel() {
    var uiState: GroupWalletAddressesUIState by mutableStateOf(initialUIState)
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val wallet = chatRepository.groupWallet(chatRoomId, chain)
            if (wallet == null) {
                uiState = GroupWalletAddressesUIState.Error("This group has not opened a wallet")
                return@launch
            }
            if (wallets == null) {
                uiState = GroupWalletAddressesUIState.CannotWatch
                return@launch
            }

            uiState = GroupWalletAddressesUIState.Loaded(receive = null)
            wallets.walletFor(wallet.account).state.collect { state ->
                uiState = GroupWalletAddressesUIState.Loaded(receive = state.receiveAddresses)
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            chain: Chain,
            initialUIState: GroupWalletAddressesUIState = GroupWalletAddressesUIState.Loading,
            chatRepository: ChatRepository,
            wallets: GroupOnchainWallets?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupWalletAddressesViewModel(
                    chatRoomId = chatRoomId,
                    chain = chain,
                    initialUIState = initialUIState,
                    chatRepository = chatRepository,
                    wallets = wallets,
                )
            }
        }
    }
}
