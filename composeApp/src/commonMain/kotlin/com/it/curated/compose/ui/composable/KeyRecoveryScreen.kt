package com.it.curated.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToDrive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.phoenix.data.ActiveWallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import curated.composeapp.generated.resources.Res
import curated.composeapp.generated.resources.cloud_backup
import curated.composeapp.generated.resources.download_and_securely_store_everything
import curated.composeapp.generated.resources.emergency_kit
import curated.composeapp.generated.resources.encrypt_and_back_your_recovery_information
import curated.composeapp.generated.resources.key_recovery
import curated.composeapp.generated.resources.not_backed_up_yet
import curated.composeapp.generated.resources.recovery_phrase
import curated.composeapp.generated.resources.these_are_your_keys_keep_them_safe_so_they
import curated.composeapp.generated.resources.write_down_and_secure_the_12_word_phrase
import curated.composeapp.generated.resources.yolo
import curated.composeapp.generated.resources.you_only_live_once_lose_this_phone_and_the
import curated.composeapp.generated.resources.you_said_you_wrote_it_down
import org.jetbrains.compose.resources.stringResource
import com.it.curated.compose.extensions.hexToNpubHrp
import com.it.curated.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.it.curated.compose.ui.composable.navigation.routes.RecoveryPhraseRoute
import com.it.curated.compose.ui.composable.navigation.routes.Route
import com.it.curated.compose.ui.composable.widgets.Decorative
import com.it.curated.compose.ui.theme.ConformancePreviews
import com.it.curated.compose.ui.theme.readableContent
import com.it.curated.compose.ui.theme.spacing

/**
 * Everything that can put this profile back on another phone.
 *
 * A Curated profile is its key: the npub that signs, and the wallet that holds
 * coins, both come out of one seed that never leaves the device. There is no
 * account to reset, so this screen is the only thing standing between a lost
 * phone and a lost identity -- it exists to get the user to take a backup while
 * they still can.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun KeyRecoveryScreen(
    activeUserPublicKey: HexKey,
    activeWalletStateFlow: StateFlow<ActiveWallet?>,
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (Route) -> Unit,
) {
    val activeWallet by activeWalletStateFlow.collectAsState()

    // The same preference the recovery phrase screen writes, so the state of the backup is
    // visible before the user goes looking for it.
    val showBackupNotice by remember(activeWallet) {
        activeWallet?.internalPrefs?.showSeedBackupNotice ?: flowOf(false)
    }.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.key_recovery)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            onNavigateBack.invoke()
                        }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .readableContent()
                .padding(horizontal = MaterialTheme.spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MaterialTheme.spacing.containerPadding),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(Res.string.these_are_your_keys_keep_them_safe_so_they),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = activeUserPublicKey.hexToNpubHrp(),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.Spellcheck,
                    title = stringResource(Res.string.recovery_phrase),
                    description = stringResource(Res.string.write_down_and_secure_the_12_word_phrase),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    status = if (showBackupNotice) {
                        KeyRecoveryStatus(
                            icon = Icons.Default.Warning,
                            text = stringResource(Res.string.not_backed_up_yet)
                        )
                    } else {
                        KeyRecoveryStatus(
                            icon = Icons.Default.CheckCircle,
                            text = stringResource(Res.string.you_said_you_wrote_it_down)
                        )
                    },
                    onClick = {
                        onNavigateToRoute.invoke(RecoveryPhraseRoute)
                    }
                )
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.AddToDrive,
                    title = stringResource(Res.string.cloud_backup),
                    description = stringResource(Res.string.encrypt_and_back_your_recovery_information),
                    onClick = {
                        onNavigateToRoute.invoke(
                            ImplementationPendingRoute("Cloud backup")
                        )
                    }
                )
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.LocalHospital,
                    title = stringResource(Res.string.emergency_kit),
                    description = stringResource(Res.string.download_and_securely_store_everything),
                    onClick = {
                        onNavigateToRoute.invoke(
                            ImplementationPendingRoute("Emergency kit")
                        )
                    }
                )
            }

            item {
                KeyRecoveryOption(
                    icon = Icons.Default.VolunteerActivism,
                    title = stringResource(Res.string.yolo),
                    description = stringResource(Res.string.you_only_live_once_lose_this_phone_and_the),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = {
                        onNavigateBack.invoke()
                    }
                )
            }

            item {
                Spacer(
                    modifier = Modifier.height(MaterialTheme.spacing.itemGap)
                )
            }
        }
    }
}

/** A one-line verdict on an option, shown under it. */
private data class KeyRecoveryStatus(
    val icon: ImageVector,
    val text: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyRecoveryOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    status: KeyRecoveryStatus? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.containerPadding),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = Decorative
                )

                Spacer(
                    modifier = Modifier.width(MaterialTheme.spacing.space125)
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall
            )

            status?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        it.icon,
                        contentDescription = Decorative,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(
                        modifier = Modifier.width(MaterialTheme.spacing.relatedGap)
                    )

                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@ConformancePreviews
@Composable
private fun KeyRecoveryScreenPreview() {
    com.it.curated.compose.ui.theme.TorchTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            KeyRecoveryScreen(
                activeUserPublicKey = "84dee6e676e5bb67b4ad4e042cf70cbd8681155db535942fcc6a0533858a7240",
                activeWalletStateFlow = MutableStateFlow(null),
                onNavigateBack = {},
                onNavigateToRoute = {}
            )
        }
    }
}
