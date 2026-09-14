package to.curare.compose.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import curare.composeapp.generated.resources.Res
import curare.composeapp.generated.resources.a_list_is_a_schema_what_an_entry_may_contain
import curare.composeapp.generated.resources.fields_colon
import curare.composeapp.generated.resources.pick_a_shape_to_start_from
import curare.composeapp.generated.resources.start_a_list
import curare.composeapp.generated.resources.template_catalogue
import curare.composeapp.generated.resources.template_catalogue_description
import curare.composeapp.generated.resources.template_custom
import curare.composeapp.generated.resources.template_custom_description
import curare.composeapp.generated.resources.template_discussion
import curare.composeapp.generated.resources.template_discussion_description
import curare.composeapp.generated.resources.template_links
import curare.composeapp.generated.resources.template_links_description
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import to.curare.compose.nostr.curated.CuratedSchemaTemplate
import to.curare.compose.ui.composable.navigation.routes.EditGroupCuratedSchemaRoute
import to.curare.compose.ui.composable.navigation.routes.Route
import to.curare.compose.ui.composable.widgets.Decorative
import to.curare.compose.ui.composable.widgets.LocalSnackbarHostState
import to.curare.compose.ui.composable.widgets.NavigateBackButton
import to.curare.compose.ui.theme.ConformancePreviews
import to.curare.compose.ui.theme.CurareTheme
import to.curare.compose.ui.theme.readableContent
import to.curare.compose.ui.theme.spacing

/**
 * The first step of adding a curated schema: which shape the list starts from.
 *
 * curare.to's "Start a list" page, which is what its `/new` shows before a
 * template is chosen: a line on what a list is, and one card per template
 * naming it, saying what it is for and listing what an entry in it asks for.
 * Tapping a card opens the editor seeded from that template, so that a list
 * started here and one started on that site are the same kind of thing --
 * and a member who wants none of the shapes takes *Custom*, which is the two
 * fields every list has and what "Add schema" used to open on directly.
 *
 * There is nothing to load and no state to keep: the templates are constants,
 * and the choice leaves as an argument to `EditGroupCuratedSchemaRoute` rather
 * than being remembered here. Back from the editor is this screen and another
 * shape, the way the browser's back is on the site.
 *
 * Gated by the screen before it, not here. "Add schema" is offered only to a
 * member holding a share of the key, and the editor says so again if a screen
 * ever reaches it another way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectCuratedSchemaTemplateScreen(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    relayHint: String?,
    onNavigateToRoute: (Route) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarHostState.current) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.start_a_list),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = { NavigateBackButton(onNavigateBack) }
            )
        }
    ) { innerPadding ->
        // A scrolling column rather than a lazy list: four cards, all of them
        // wanted at once, and nothing under them to page in.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .readableContent()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.screenMargin,
                    vertical = MaterialTheme.spacing.itemGap
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.itemGap)
        ) {
            // At the leading edge like the cards under it, whose icons and
            // headlines it lines up with; the editor after this one centres
            // its lead because nothing on it has an edge to keep.
            Text(
                text = stringResource(Res.string.a_list_is_a_schema_what_an_entry_may_contain),
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = stringResource(Res.string.pick_a_shape_to_start_from),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            CuratedSchemaTemplate.entries.forEach { template ->
                CuratedSchemaTemplateCard(
                    template = template,
                    onClick = {
                        onNavigateToRoute.invoke(
                            EditGroupCuratedSchemaRoute(
                                activeUserPublicKey = activeUserPublicKey,
                                chatRoomId = chatRoomId,
                                relayHint = relayHint,
                                identifier = null,
                                template = template.key
                            )
                        )
                    }
                )
            }
        }
    }
}

/**
 * One shape a list can start from, as a card that opens the editor on it.
 *
 * The site's card is the name, the description and a count of fields. The
 * count is the one thing changed: the fields are listed by label instead, the
 * way the group's own schema cards list them, because what a member choosing
 * a shape wants to know is what an entry in it will ask for, and "6 fields"
 * answers a different question. The identifier is among them although a form
 * never asks for it: the editor lists it as a row too, marked derived, and the
 * card shows the rows the editor is about to.
 */
@Composable
private fun CuratedSchemaTemplateCard(
    template: CuratedSchemaTemplate,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        ListItem(
            leadingContent = {
                // Decorative: the name beside it is the label, and the card is
                // the target.
                Icon(template.icon(), contentDescription = Decorative)
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = Decorative)
            },
            headlineContent = {
                Text(
                    text = stringResource(template.label()),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.relatedGap)
                ) {
                    Text(text = stringResource(template.description()))

                    // Unclipped, unlike the group's schema cards: a template's
                    // fields are this build's own and ten at most, and the list
                    // is what the choice is made on.
                    Text(
                        text = stringResource(
                            Res.string.fields_colon,
                            template.fields.joinToString { it.labelOrName() }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        )
    }
}

/**
 * What the picker calls a template.
 *
 * A `when` rather than a field on each entry, for the reason `CuratedVisibility`
 * has one: the enum stays the protocol's four shapes with no copy behind it,
 * and a fifth is a compile error here rather than a card with no name.
 */
private fun CuratedSchemaTemplate.label(): StringResource = when (this) {
    CuratedSchemaTemplate.Links -> Res.string.template_links
    CuratedSchemaTemplate.Discussion -> Res.string.template_discussion
    CuratedSchemaTemplate.Catalogue -> Res.string.template_catalogue
    CuratedSchemaTemplate.Custom -> Res.string.template_custom
}

/** What the template is for, in the site's words. */
private fun CuratedSchemaTemplate.description(): StringResource = when (this) {
    CuratedSchemaTemplate.Links -> Res.string.template_links_description
    CuratedSchemaTemplate.Discussion -> Res.string.template_discussion_description
    CuratedSchemaTemplate.Catalogue -> Res.string.template_catalogue_description
    CuratedSchemaTemplate.Custom -> Res.string.template_custom_description
}

/** The picture of the shape: what the entries mostly are, or the sliders for none in particular. */
private fun CuratedSchemaTemplate.icon(): ImageVector = when (this) {
    CuratedSchemaTemplate.Links -> Icons.Default.Link
    CuratedSchemaTemplate.Discussion -> Icons.AutoMirrored.Filled.Chat
    CuratedSchemaTemplate.Catalogue -> Icons.Default.Category
    CuratedSchemaTemplate.Custom -> Icons.Default.Tune
}

@ConformancePreviews
@Composable
private fun SelectCuratedSchemaTemplateScreenPreview() {
    CurareTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SelectCuratedSchemaTemplateScreen(
                activeUserPublicKey = "a".repeat(64),
                chatRoomId = "d".repeat(64),
                relayHint = null,
                onNavigateToRoute = {},
                onNavigateBack = {},
            )
        }
    }
}
