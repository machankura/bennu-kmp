package com.machankura.bennu.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.bennu.compose.managers.MemberWalletManager
import com.machankura.bennu.compose.repository.MemberWalletRepository
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A person's guarded wallets as the groups this profile is in guard them --
 * the person's page's *Wallets your groups guard*. Where a group's page lists a
 * member's wallet, its row leads here, since a person is someone to look at,
 * not a room to go into.
 */
class PersonGuardedWalletsViewModel(
    private val ownerPublicKey: HexKey,
    private val memberWalletRepository: MemberWalletRepository,
) : ViewModel() {
    var rows: List<MemberWalletManager.GuardedHere> by mutableStateOf(emptyList())
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) { rows = memberWalletRepository.guardedByGroupsHere(ownerPublicKey) }
    }

    companion object {
        fun factory(ownerPublicKey: HexKey, memberWalletRepository: MemberWalletRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { PersonGuardedWalletsViewModel(ownerPublicKey, memberWalletRepository) }
        }
    }
}
