package com.it.curated.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey

abstract class SignInToProfileUIState {
    data object Error: SignInToProfileUIState()

    data object InputPrompt: SignInToProfileUIState()

    data class ConfirmNpubSignIn(
        val npub: String,
        val hexKey: HexKey
    ) : SignInToProfileUIState()

    data class ConfirmNsecSignIn(
        val nsec: String,
        val hexKey: HexKey
    ) : SignInToProfileUIState()

}