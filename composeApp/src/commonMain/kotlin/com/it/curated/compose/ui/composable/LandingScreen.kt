package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.it.curated.compose.ui.theme.spacing
import curated.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import curated.composeapp.generated.resources.create_profile
import curated.composeapp.generated.resources.if_you_are_new_to_torch_or_just_want_to
import curated.composeapp.generated.resources.keep_the_feed_alive
import curated.composeapp.generated.resources.learn_more
import curated.composeapp.generated.resources.curated
import curated.composeapp.generated.resources.sign_in
import curated.composeapp.generated.resources.sign_in_to_torch_via_nsec_or_remote_signer
import androidx.compose.material3.SnackbarHost
import com.it.curated.compose.ui.composable.widgets.LocalSnackbarHostState
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.theme.ConformancePreviews

@Composable
fun LandingScreen(
    onNavigateToLearnMore: () -> Unit,
    onNavigateToSignIn: () -> Unit,
    onNavigateToCreateProfile: () -> Unit
) {
    Scaffold(snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) }) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding)
            .readableContent()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.space125),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(Res.string.curated),
                    style = MaterialTheme.typography.headlineLarge
                )

                Text(
                    text = stringResource(Res.string.keep_the_feed_alive)
                )

                TextButton(
                    onClick = onNavigateToLearnMore
                ) {
                    Text(
                        text = stringResource(Res.string.learn_more),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Spacer(
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = onNavigateToSignIn
                ) {

                    Text(
                        stringResource(Res.string.sign_in),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                Text(
                    text = stringResource(Res.string.sign_in_to_torch_via_nsec_or_remote_signer),
                    modifier = Modifier.padding(MaterialTheme.spacing.space125),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )

                Spacer(
                    modifier = Modifier.height(MaterialTheme.spacing.space250)
                )

                Button(
                    onClick = onNavigateToCreateProfile
                ) {
                    Text(
                        stringResource(Res.string.create_profile),
                        style = MaterialTheme.typography.bodyLarge
                    )

                }
                Text(
                    text = stringResource(Res.string.if_you_are_new_to_torch_or_just_want_to),
                    modifier = Modifier.padding(MaterialTheme.spacing.space125),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun LandingScreenPreview() {
    com.it.curated.compose.ui.theme.TorchTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            LandingScreen(
                onNavigateToSignIn = {},
                onNavigateToLearnMore = {},
                onNavigateToCreateProfile = {}
            )
        }
    }
}