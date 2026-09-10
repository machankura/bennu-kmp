package to.curare.compose.ui.composable.widgets

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** A labelled value row for detail screens (label above, value below). */
@Composable
fun DetailRow(label: String, value: String) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Text(value) }
    )
}
