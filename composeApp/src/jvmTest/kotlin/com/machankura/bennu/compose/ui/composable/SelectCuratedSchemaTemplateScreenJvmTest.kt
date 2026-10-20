package com.machankura.bennu.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.bennu.compose.nostr.curated.CuratedSchemaTemplate
import com.machankura.bennu.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.bennu.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.bennu.compose.ui.composable.navigation.routes.EditGroupCuratedSchemaRoute
import com.machankura.bennu.compose.ui.composable.navigation.routes.Route
import com.machankura.bennu.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.bennu.compose.ui.theme.BennuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first step of adding a schema: the shapes a list can start from, one
 * card each, and the editor opened on the one tapped.
 *
 * **Every shape is offered, in the site's order, saying what it asks for.** A
 * member choosing a shape reads the fields to choose, so the card lists them,
 * and the list is the template's own -- a card that summarised a template in
 * its own words could drift from what the editor then opens on.
 *
 * **A card opens the editor on its template and nothing else.** The route
 * carries the key and no identifier, which is the whole of what the picker
 * decides; the editor seeds from the key, so a wrong one here is a form that
 * opens on the wrong fields with nothing saying so.
 */
@OptIn(ExperimentalTestApi::class)
class SelectCuratedSchemaTemplateScreenJvmTest {

    @Test
    fun `every shape is offered, in the site's order, with what an entry in it asks for`() = render {
        onNodeWithText("Start a list").assertIsDisplayed()
        onNodeWithText("A list is a schema", substring = true).assertIsDisplayed()

        val links = onNodeWithText("Links & text").assertIsDisplayed().getBoundsInRoot().top
        val discussion = onNodeWithText("Discussion").assertIsDisplayed().getBoundsInRoot().top
        val catalogue = onNodeWithText("Catalogue").assertIsDisplayed().getBoundsInRoot().top
        val custom = onNodeWithText("Custom").assertIsDisplayed().getBoundsInRoot().top
        assertTrue(
            links < discussion && discussion < catalogue && catalogue < custom,
            "the shapes were drawn out of order"
        )

        // What each asks for, by label, the identifier included: the editor's
        // row for it is there too and says it is derived.
        onNodeWithText("Fields: Identifier, Title, Link, Text, Picture, Flair").assertIsDisplayed()
        onNodeWithText("Fields: Identifier, Title, Text, Flair").assertIsDisplayed()
        onNodeWithText(
            "Fields: Identifier, Title, Year, Type, External id, Where to see it, Reference, " +
                "Picture, Language, Description"
        ).assertIsDisplayed()
        onNodeWithText("Fields: Identifier, Title").assertIsDisplayed()

        // And what each is for, in the site's words.
        onNodeWithText("Reddit’s default", substring = true).assertIsDisplayed()
        onNodeWithText("bitcoin.mov’s shape", substring = true).assertIsDisplayed()

        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `a card opens the editor on its template, and on no schema`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Catalogue").performClick()
        }

        assertEquals(
            EditGroupCuratedSchemaRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                identifier = null,
                template = CuratedSchemaTemplate.Catalogue.key
            ),
            opened
        )
    }

    private fun render(
        onNavigateToRoute: (Route) -> Unit = {},
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 1600) {
        setContent {
            BennuTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SelectCuratedSchemaTemplateScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        assertions()
    }
}
