package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.it.curated.compose.repository.MarmotRepository
import com.it.curated.compose.ui.theme.LocalExtendedColors
import com.it.curated.compose.ui.view.model.CreateProfileViewModel
import com.it.curated.compose.ui.view.state.CreateProfileUIState
import com.it.curated.compose.ui.theme.spacing
import curated.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curated.composeapp.generated.resources.after_this_there_is_no_turning_back
import curated.composeapp.generated.resources.bio
import curated.composeapp.generated.resources.create_profile
import curated.composeapp.generated.resources.end_this
import curated.composeapp.generated.resources.enter_the_name_you_want_to_use_for_your_2
import curated.composeapp.generated.resources.introduce_yourself
import curated.composeapp.generated.resources.it_is_cryptographical_secure_and
import curated.composeapp.generated.resources.name
import curated.composeapp.generated.resources.name_eg_alan_turing
import curated.composeapp.generated.resources.next
import curated.composeapp.generated.resources.profile_is_ready
import curated.composeapp.generated.resources.see_how_deep_the_rabbit_hole_goes
import curated.composeapp.generated.resources.something_went_wrong_and_we_were_unable_to_2
import curated.composeapp.generated.resources.start
import curated.composeapp.generated.resources.the_above_will_be_your_profile
import curated.composeapp.generated.resources.this_will_be_shown_when_people_open_your
import curated.composeapp.generated.resources.this_will_be_the_display_name_for_your
import curated.composeapp.generated.resources.what_should_people_know_about_you
import curated.composeapp.generated.resources.you_are_about_to_create_a_nostr_profile
import curated.composeapp.generated.resources.you_will_be_in_full_control_of_this_profile
import androidx.compose.material3.SnackbarHost
import com.it.curated.compose.ui.composable.widgets.LocalSnackbarHostState
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.theme.ConformancePreviews

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CreateProfileScreen(
    initialCreateProfileUIState: CreateProfileUIState = CreateProfileUIState.Declaration,
    onNavigateToEndThis: () -> Unit,
    nostrRepository: com.it.curated.compose.repository.NostrRepository,
    marmotRepository: MarmotRepository,
    writeSeed: (mnemonics: List<String>, onSeedWritten: () -> Unit, onSeedWriteError: () -> Unit) -> Unit
) {
    val createProfileViewModel: CreateProfileViewModel =  viewModel (
        factory = CreateProfileViewModel.factory(
            initialCreateProfileUIState,
            nostrRepository,
            marmotRepository = marmotRepository
        )
    )
    // imePadding: this screen has a text field, and without it the software
    // keyboard covers whatever is being typed into.
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        modifier = Modifier.imePadding()) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
            .readableContent()
        ) {

            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    MaterialTheme.spacing.space125
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space250)
            ) {

                when (val createAccountUIState = createProfileViewModel.createProfileUIState.value) {
                    is CreateProfileUIState.Declaration -> {

                        Text(
                            stringResource(Res.string.create_profile),
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Text(
                            stringResource(Res.string.you_are_about_to_create_a_nostr_profile),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            stringResource(Res.string.it_is_cryptographical_secure_and),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {

                                createProfileViewModel.createProfileUIState.value = CreateProfileUIState.InputPrompt
                            }
                        ) {
                            Text(
                                text = stringResource(Res.string.start)
                            )
                        }
                    }
                    is CreateProfileUIState.InputPrompt -> {
                        Text(
                            stringResource(Res.string.create_profile),
                            style = MaterialTheme.typography.headlineSmall
                        )

                        TextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = createProfileViewModel.createProfileFormState.nameField.textFieldState,
//                            value = createProfileViewModel.createProfileFormState.nameField.text.value,
//                            onValueChange = {
//                                createProfileViewModel.createProfileFormState.nameField.text.value = it
//                            },
                            isError = createProfileViewModel.createProfileFormState.nameField.errorMessage.value != null,
                            supportingText = createProfileViewModel.createProfileFormState.nameField.errorMessage.value?.let {
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
                                    text = stringResource(Res.string.name_eg_alan_turing),
                                    maxLines = 1,
                                )
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(Res.string.enter_the_name_you_want_to_use_for_your_2),
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
                            text = stringResource(Res.string.this_will_be_the_display_name_for_your),
                            style = MaterialTheme.typography.labelMedium
                        )

                        TextField(
                            modifier = Modifier.fillMaxWidth(),
                            state = createProfileViewModel.createProfileFormState.biographyField.textFieldState,
                            isError = createProfileViewModel.createProfileFormState.biographyField.errorMessage.value != null,
                            supportingText = createProfileViewModel.createProfileFormState.biographyField.errorMessage.value?.let {
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
                                    text = stringResource(Res.string.introduce_yourself),
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
                            text = stringResource(Res.string.this_will_be_shown_when_people_open_your),
                            style = MaterialTheme.typography.labelMedium
                        )

                        Button(
                            onClick = {

                                createProfileViewModel.validateInput().let { isInputValid ->
                                    if (isInputValid) {
                                        createProfileViewModel.createProfileUIState.value =
                                            CreateProfileUIState.ConfirmInput(
                                                name = createProfileViewModel.createProfileFormState.nameField.textFieldState.text.toString(),
                                                bio = createProfileViewModel.createProfileFormState.biographyField.textFieldState.text.toString()
                                            )
                                    }
                                }


                            }
                        ) {
                            Text(
                                stringResource(Res.string.next)
                            )
                        }

                    }
                    is CreateProfileUIState.ConfirmInput -> {

                        // A label/value list, so every one of the five is full width and
                        // starts at the same edge. Centred, each floated at its own width
                        // and "Name" began somewhere other than the name under it.
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(Res.string.name),
                            style = MaterialTheme.typography.labelLarge
                        )

                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = createAccountUIState.name,
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(Res.string.bio),
                            style = MaterialTheme.typography.labelLarge
                        )

                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = createAccountUIState.bio,
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(Res.string.the_above_will_be_your_profile),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = stringResource(Res.string.you_will_be_in_full_control_of_this_profile),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )

                        if (createProfileViewModel.isActionPending.value) {
                            LoadingIndicator(
                                modifier = Modifier
                            )
                        } else {
                            Button(
                                onClick = {
                                    createProfileViewModel.createAccount(writeSeed)
                                }
                            ) {
                                Text(
                                    text = stringResource(Res.string.create_profile)
                                )
                            }
                        }

                    }
                    is CreateProfileUIState.Error -> {
                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(Res.string.something_went_wrong_and_we_were_unable_to_2),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                    }
                    is CreateProfileUIState.ProfileReady -> {
                        // Both pills come from the extended families rather than from a
                        // literal paired with Color.White/Color.DarkGray by eye. The old
                        // pairings read 4.57:1 and 2.90:1 against their containers, and
                        // RedPill carried alpha 0.749, so composited over the surface the
                        // first was really 3.50:1. Both are below the 4.5:1 floor.
                        val extendedColors = LocalExtendedColors.current

                        Spacer(
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(Res.string.profile_is_ready),
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = stringResource(Res.string.after_this_there_is_no_turning_back)
                        )

                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = extendedColors.redPill.color,
                                contentColor = extendedColors.redPill.onColor
                            ),
                            onClick = {
                                createProfileViewModel.createAccount(writeSeed)
                            }
                        ) {
                            Text(
                                text = stringResource(Res.string.see_how_deep_the_rabbit_hole_goes)
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(MaterialTheme.spacing.space250)
                        )

                        Button(
                            colors = ButtonDefaults.buttonColors(
                                containerColor = extendedColors.bluePill.color,
                                contentColor = extendedColors.bluePill.onColor
                            ),
                            onClick = {
                                // TODO: Delete everything and close the app
                                createProfileViewModel.wipeDatabase {
                                    onNavigateToEndThis.invoke()
                                }
                            }
                        ) {
                            Text(
                                text = stringResource(Res.string.end_this)
                            )
                        }
                        Spacer(
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
fun CreateAccountScreenPreview() {
    com.it.curated.compose.ui.theme.TorchTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            CreateProfileScreen(
                initialCreateProfileUIState =
                    CreateProfileUIState.InputPrompt,
//                    CreateProfileUIState.Error,
//                    CreateProfileUIState.ConfirmInput(
//                        name = "Alan Turing",
//                        bio = "Polyglot: Math... is my middle name, computer scientist. cryptographer, and gold hider."
//                    ),
//                    CreateProfileUIState.UnsignedProfile(
//                        unsignedNostrEvent = UnsignedNostrEvent(
//                            kind = 0,
//                            content = "Something here",
//                            tags = emptyArray(),
//                        ),
//                        name = "Alan Turing",
//                        bio = "Polyglot: Math... is my middle name, computer scientist. cryptographer, and gold hider."
//                    ),
//                    CreateProfileUIState.UnbroadcastedProfile(
//                        nostrEvent = NostrEvent(
//                            id = "",
//                            pubKey = "",
//                            kind = 0,
//                            content = "Something here",
//                            tags = emptyArray(),
//                            sig = ""
//                        ),
//                        name = "Alan Turing",
//                        bio = "Polyglot: Math... is my middle name, computer scientist. cryptographer, and gold hider."
//                    ),
//                    com.it.curated.compose.ui.view.state.CreateProfileUIState.ProfileReady(
//                        profile = com.it.curated.compose.database.model.Profile(
//                            publicKey = "",
//                            nostrEventId = "",
//                            displayName = "Alan Turing",
//                            about = "Polyglot: Math... is my middle name, computer scientist. cryptographer, and gold hider."
//                        )
//                    ),
                onNavigateToEndThis = {},
                writeSeed = { _, _, _ -> },
                nostrRepository = com.it.curated.compose.repository.NostrRepository.NO_OP_NOSTR_REPOSITORY,
                marmotRepository = MarmotRepository.NO_OP_MARMOT_KEY_PACKAGE_BUNDLE
            )
        }
    }
}