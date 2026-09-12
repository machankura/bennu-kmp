/*
 * Copyright 2025 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package to.curare.compose.ui.composable.widgets.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.curare.compose.ui.composable.widgets.buttons.Clickable
import fr.acinq.phoenix.data.WalletId
import fr.acinq.phoenix.utils.preferences.GlobalPrefs
import fr.acinq.phoenix.utils.preferences.UserWalletMetadata
import fr.acinq.phoenix.utils.preferences.getByWalletIdOrDefault
import to.curare.compose.extensions.hexToNpubHrp
import to.curare.compose.identity.StoredIdentity
import to.curare.compose.ui.theme.spacing

@Composable
fun WalletsSelector(
    modifier: Modifier = Modifier,
    globalPrefs: GlobalPrefs,
    wallets: Map<WalletId, StoredIdentity>,
    walletsMetadata: Map<WalletId, UserWalletMetadata>,
    activeWalletId: WalletId?,
    canEdit: Boolean,
    onWalletClick: (StoredIdentity) -> Unit,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    topContent: @Composable (() -> Unit)? = null,
    bottomContent: @Composable (() -> Unit)? = null,
) {

    val currentWallet = remember(wallets) { wallets.entries.firstOrNull { it.key == activeWalletId }?.value }
    val otherWalletsList = remember(wallets, walletsMetadata) { wallets.entries.filterNot { it.key == activeWalletId || walletsMetadata[it.key]?.isHidden == true }.toList() }

    LazyColumn(modifier = modifier, verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment) {
        topContent?.let {
            item { it.invoke() }
        }
        if (currentWallet != null) {
            item {
                AvailableWalletView(
                    identity = currentWallet,
                    globalPrefs = globalPrefs,
                    metadata = walletsMetadata.getByWalletIdOrDefault(currentWallet.id),
                    isCurrent = true,
                    canEdit = canEdit,
                    onClick = { onWalletClick(currentWallet) }
                )
                Spacer(Modifier.height(MaterialTheme.spacing.space150))
                HorizontalDivider()
                Spacer(Modifier.height(MaterialTheme.spacing.space100))
            }
        }
        items(items = otherWalletsList) { (walletId, identity) ->
            AvailableWalletView(
                identity = identity,
                globalPrefs = globalPrefs,
                metadata = walletsMetadata.getByWalletIdOrDefault(walletId),
                isCurrent = false,
                canEdit = canEdit,
                onClick = { onWalletClick(identity) }
            )
            Spacer(Modifier.height(MaterialTheme.spacing.space100))
        }
        bottomContent?.let {
            item { it.invoke() }
        }
    }
}

/**
 * The second line is the npub for both kinds. It used to be the node id, which an
 * identity made from a bare key does not have -- and the npub is the identifier the rest
 * of the app shows, and the one a user might actually recognise.
 */
@Composable
private fun AvailableWalletView(
    modifier: Modifier = Modifier,
    globalPrefs: GlobalPrefs,
    identity: StoredIdentity,
    metadata: UserWalletMetadata,
    isCurrent: Boolean,
    canEdit: Boolean,
    onClick: (WalletId) -> Unit,
) {
    var showWalletEditDialog by remember { mutableStateOf(false) }
    if (showWalletEditDialog) {
        to.curare.compose.ui.composable.widgets.LoadingDataIndicator(
            text = "Edit function coming soon"
        )
//        EditWalletDialog(
//            onDismiss = { showWalletEditDialog = false },
//            walletId = identity.id,
//            globalPrefs = globalPrefs,
//            metadata = metadata,
//        )
    }

    Clickable(
        modifier = modifier,
        onClick = {
            if (isCurrent) {
                if (canEdit) showWalletEditDialog = true else return@Clickable
            } else {
                onClick(identity.id)
            }
        },
        shape = RoundedCornerShape(16.dp),
        backgroundColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.spacing.space150, vertical = MaterialTheme.spacing.space150)
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WalletAvatar(avatar = metadata.avatar, backgroundColor = Color.Transparent, internalPadding = PaddingValues(MaterialTheme.spacing.space50))
                Spacer(Modifier.width(MaterialTheme.spacing.space150))
                Column {
                    Text(text = metadata.nameOrDefault(), modifier = Modifier, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MaterialTheme.spacing.space25))
                    Text(text = identity.nostrPublicKey.hexToNpubHrp(), modifier = Modifier, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                }
            }
            if (isCurrent && canEdit) {
                Spacer(Modifier.width(MaterialTheme.spacing.space100))
//              TODO:  PhoenixIcon(R.drawable.ic_edit, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(MaterialTheme.spacing.space200))
            }
        }
    }
}
