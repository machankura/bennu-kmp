package to.curare.compose.ui.composable.widgets.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Start
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import to.curare.compose.extensions.bech32ToHexOrNull
import to.curare.compose.ui.composable.navigation.routes.ChatRoomMessagingRoute
import to.curare.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.theme.CurareTheme
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.utils.Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import to.curare.compose.ui.theme.spacing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.input_npub_or_nip05
import curare.composeapp.generated.resources.start_chat
import curare.composeapp.generated.resources.start_chat_via_npub_or_nip05
import to.curare.compose.ui.theme.ConformancePreviews

@Composable
fun StartDirectMessageToNpubOrNip05Dialog(
    activeUserPublicKey: HexKey,
    scope: CoroutineScope,
    toggleOpenDialogSetting: (Boolean) -> Unit,
    onNavigateToRoute: (Route) -> Unit
) {

    Dialog(
        onDismissRequest = {

        }
    ) {
        val startChatButtonEnabled = remember { mutableStateOf(false) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(MaterialTheme.spacing.containerPadding),
            shape = RoundedCornerShape(16.dp),
        ) {
            val textFieldState = rememberTextFieldState()

            // M3's flow guidance for a dialog: "Focus is set to the dialog component,
            // likely to a specific interactive element within the dialog such as a text
            // input field." This dialog is one field and two buttons, so the field is
            // unambiguous -- and without this the whole dialog opens with nothing
            // focused, which for a keyboard or switch user means tabbing in from
            // wherever focus happened to be.
            val npubFieldFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { npubFieldFocus.requestFocus() }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {
                Spacer(
                    modifier = Modifier.height(MaterialTheme.spacing.space50)
                )
                Text(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = MaterialTheme.spacing.space250,
                    ),
                    text = stringResource(Res.string.start_chat_via_npub_or_nip05),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                TextField(
                    modifier = Modifier.focusRequester(npubFieldFocus),
                    state = textFieldState,
                    placeholder = {
                        Text(stringResource(Res.string.input_npub_or_nip05))
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                scope.launch {

                                    toggleOpenDialogSetting.invoke(false)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    inputTransformation = {
                        val input = asCharSequence().toString().lowercase()

                        if (input.startsWith("npub1") || input.startsWith("nostr:npub1")) {
                            // Check for valid npub
                            startChatButtonEnabled.value = true
                        } else if (Parser.parseEmailLikeAddress(input) != null) {
                            // Check for valid npub
                            startChatButtonEnabled.value = true
                        } else {
                            startChatButtonEnabled.value = false
                        }
                    }
                )

                TextButton(
                    enabled = startChatButtonEnabled.value,
                    onClick = {
                        scope.launch {
                            toggleOpenDialogSetting.invoke(false)
                        }.invokeOnCompletion {
                            val input = textFieldState.text.toString()

                            val emailLikeAddress = Parser.parseEmailLikeAddress(input)

                            if (emailLikeAddress != null) {
                                // TODO: Get public Key from hex...
                                onNavigateToRoute.invoke(
                                    ImplementationPendingRoute(
                                        "Direct Message to ${textFieldState.text}"
                                    )
                                )
                            } else {
                                val publicKey = input.bech32ToHexOrNull()

                                if (publicKey != null) {
                                    onNavigateToRoute.invoke(
                                        ChatRoomMessagingRoute(
                                            activeUserPublicKey = activeUserPublicKey,
                                            chatRoomId = publicKey,
                                            relayHint = null
                                        )
                                    )
                                }
                            }

                        }
                    }
                ) {
                    Text(stringResource(Res.string.start_chat))

                    Spacer(
                        modifier = Modifier.width(MaterialTheme.spacing.space125)
                    )

                    Icon(
                        Icons.Default.Start,
                        contentDescription = "Start chat"
                    )
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun StartDirectMessageToNpubOrNip05DialogPreview() {
    CurareTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            StartDirectMessageToNpubOrNip05Dialog(
                activeUserPublicKey = "hex",
                scope = rememberCoroutineScope(),
                toggleOpenDialogSetting = {},
                onNavigateToRoute = {}
            )
        }
    }
}