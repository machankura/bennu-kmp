package com.it.curated.compose.ui.composable.widgets.profile

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Face5
import androidx.compose.material.icons.filled.FaceRetouchingOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.it.curated.compose.ui.theme.spacing
import com.it.curated.compose.ui.theme.ConformancePreviews

@Composable
fun ProfileAvatar(
    size: Dp = 55.dp,
    shape: Shape = CircleShape,
    profile: com.it.curated.compose.database.model.Profile
) {
    ProfileAvatar(
        size = size,
        shape = shape,
        profile = profile,
        publicKey = profile.publicKey
    )
}

@Composable
fun ProfileAvatar(
    size: Dp = 55.dp,
    shape: Shape = CircleShape,
    publicKey: String,
    profile: com.it.curated.compose.database.model.Profile?
) {
    val modifier = Modifier.size(size).clip(shape = shape)

    val tintColor = ProfileColor.fromPublicKey(
        publicKey
    )

    if (profile?.picture == null) {
        Icon(
            modifier = modifier,
            imageVector = Icons.Default.AccountCircle,
            tint = tintColor,
            contentDescription = "Profile picture",
        )
    } else {
        // Load image...
        val placeHolderGraphic =
            com.it.curated.compose.ui.composable.widgets.coil.forwardingPainter(
                painter = rememberVectorPainter(Icons.Default.Face5),
                colorFilter = ColorFilter.tint(tintColor)
            )

        val fallbackGraphic =
            com.it.curated.compose.ui.composable.widgets.coil.forwardingPainter(
                painter = rememberVectorPainter(Icons.Default.Face5),
                colorFilter = ColorFilter.tint(tintColor)
            )
        val errorGraphic =
            com.it.curated.compose.ui.composable.widgets.coil.forwardingPainter(
                painter = rememberVectorPainter(Icons.Default.FaceRetouchingOff),
                colorFilter = ColorFilter.tint(tintColor)
            )

        AsyncImage(
            model = profile.picture,
            modifier = modifier,
            contentDescription = "Profile picture for ${profile.displayName ?: profile.publicKey}",
            placeholder = placeHolderGraphic,
            fallback = fallbackGraphic,
            error = errorGraphic,
            contentScale = ContentScale.Crop
        )
    }
    // TODO: Show if we follow user...
    // TODO: Show cards...
}

@ConformancePreviews
@Composable
fun ProfileAvatarPreview() {
    com.it.curated.compose.ui.theme.TorchTheme {
        Surface(
            modifier = Modifier.padding(MaterialTheme.spacing.space250)
        ) {
            ProfileAvatar(
                publicKey = "hwgwefwase",
                profile = com.it.curated.compose.database.model.Profile(
                    publicKey = "",
                    nostrEventId = "referenced",
                    displayName = "Frank Talk",
                    picture = "pictureUrl"
                )
            )
        }
    }
}