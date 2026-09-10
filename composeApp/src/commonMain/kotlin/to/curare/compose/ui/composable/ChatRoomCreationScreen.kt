package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.view.model.ChatRoomCreationViewModel
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import to.curare.compose.ui.theme.spacing
import curare.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curare.composeapp.generated.resources.choose_who_to_chat_with
import curare.composeapp.generated.resources.enter_the_name_you_want_to_use_for_your
import curare.composeapp.generated.resources.name_eg_group_discussions
import curare.composeapp.generated.resources.this_will_be_shown_when_people_open_the_chat
import curare.composeapp.generated.resources.this_will_be_the_display_name_for_this_chat
import curare.composeapp.generated.resources.what_should_people_know_about_you
import curare.composeapp.generated.resources.what_will_be_discussed_in_this_chat_room
import androidx.compose.material3.SnackbarHost
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.ConformancePreviews

@Composable
fun ChatRoomCreationScreen(
    activeUserPublicKey: HexKey,
    onNavigateToRoute: (Route) -> Unit
) {
    val chatRoomCreationViewModel: ChatRoomCreationViewModel =  viewModel (
        factory = ChatRoomCreationViewModel.factory()
    )

    // imePadding: this screen has a text field, and without it the software
    // keyboard covers whatever is being typed into.
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        modifier = Modifier.imePadding()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).readableContent().fillMaxWidth()
        ) {

            Column(
                modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.space125),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125)
            ) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = chatRoomCreationViewModel.chatRoomCreationFormState.nameField.textFieldState,
                    isError = chatRoomCreationViewModel.chatRoomCreationFormState.nameField.errorMessage.value != null,
                    supportingText = chatRoomCreationViewModel.chatRoomCreationFormState.nameField.errorMessage.value?.let {
                        {
                            Text(
                                text = it,
                                modifier = Modifier.padding(
                                    bottom = MaterialTheme.spacing.compactPadding
                                )
                            )
                        }
                    },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                    ),
                    label = {
                        Text(
                            text = stringResource(Res.string.name_eg_group_discussions),
                            maxLines = 1,
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.enter_the_name_you_want_to_use_for_your),
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Badge,
                            contentDescription = "Name"
                        )
                    }
                )

                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.this_will_be_the_display_name_for_this_chat),
                    style = MaterialTheme.typography.labelMedium
                )

                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = chatRoomCreationViewModel.chatRoomCreationFormState.descriptionField.textFieldState,
                    isError = chatRoomCreationViewModel.chatRoomCreationFormState.descriptionField.errorMessage.value != null,
                    supportingText = chatRoomCreationViewModel.chatRoomCreationFormState.descriptionField.errorMessage.value?.let {
                        {
                            Text(
                                text = it,
                                modifier = Modifier.padding(
                                    bottom = MaterialTheme.spacing.compactPadding
                                )
                            )
                        }
                    },
                    lineLimits = TextFieldLineLimits.MultiLine(4),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                    ),
                    label = {
                        Text(
                            text = stringResource(Res.string.what_will_be_discussed_in_this_chat_room),
                            maxLines = 1,

                            )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(Res.string.what_should_people_know_about_you),
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = "Name"
                        )
                    }
                )

                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.this_will_be_shown_when_people_open_the_chat),
                    style = MaterialTheme.typography.labelMedium
                )


                Button(
                    onClick = {
                        chatRoomCreationViewModel.selectMembers(
                            activeUserPublicKey = activeUserPublicKey,
                            onNavigateToRoute = onNavigateToRoute
                        )
                    }
                ) {
                    Text(
                        stringResource(Res.string.choose_who_to_chat_with)
                    )
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun LoadingScreenPreview() {
    to.curare.compose.ui.theme.CurareTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            ChatRoomCreationScreen(
                activeUserPublicKey = "",
                onNavigateToRoute = {}
            )
        }
    }
}