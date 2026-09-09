package com.it.curated.compose.ui

import kotlinx.coroutines.runBlocking
import curated.composeapp.generated.resources.Res
import curated.composeapp.generated.resources.currently_no_messages_have_been_shared
import curated.composeapp.generated.resources.don_t_sign
import curated.composeapp.generated.resources.something_went_wrong
import curated.composeapp.generated.resources.add_chapter_to
import curated.composeapp.generated.resources.chapter_words_characters
import curated.composeapp.generated.resources.sent_a_private_message_to
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The string catalogue reads back what the source used to say.
 *
 * Moving 315 literals out of composables and into `strings.xml` is a rename that a
 * compiler cannot check: every call site still compiles if a resource holds the wrong
 * text, and the mistake surfaces as wrong words on a screen nobody has opened.
 *
 * The escapes are the part worth a test rather than an assumption. A string going into an
 * XML resource needs `'` escaped as `\'` and `%` doubled, and the generated `.cvr` stores
 * the escaped form verbatim -- base64 of `We couldn\'t find...`. Whether the backslash is
 * still there when `stringResource` hands the text to a `Text` is a property of the
 * resources library, not of the extraction, and the failure mode is an app that literally
 * displays `Don\'t sign`.
 *
 * `getString` is the non-composable reader for the same resources, so this can assert it
 * without a composition.
 */
class StringCatalogueJvmTest {

    @Test
    fun `an apostrophe survives the round trip through the catalogue`() = runBlocking {
        assertEquals("Don't sign", getString(Res.string.don_t_sign))
    }

    @Test
    fun `an escaped newline comes back as a newline`() = runBlocking {
        assertEquals(
            "Currently no messages have been shared.\nBreak the ice.",
            getString(Res.string.currently_no_messages_have_been_shared),
        )
    }

    @Test
    fun `a plain string comes back unchanged`() = runBlocking {
        assertEquals("Something went wrong", getString(Res.string.something_went_wrong))
    }

    @Test
    fun `a format string substitutes its arguments in order`() = runBlocking {
        // The interpolated strings became `%1$s` format strings, and the argument order
        // is decided by where each `${…}` sat in the template. A transposition compiles
        // and reads plausibly -- "Recovered 3 of 12" against "Recovered 12 of 3" -- so
        // the ordering is worth asserting on a multi-argument one.
        assertEquals(
            "Add chapter to Genesis",
            getString(Res.string.add_chapter_to, "Genesis"),
        )
        assertEquals(
            "Ada sent a private message to Grace",
            getString(Res.string.sent_a_private_message_to, "Ada", "Grace"),
        )
    }

    @Test
    fun `a unicode escape was decoded into the catalogue`() = runBlocking {
        // `·` is Kotlin source syntax with no meaning in XML, so the extractor
        // decodes it and the resource holds the character. If it had been left alone the
        // app would render the six characters of the escape.
        assertEquals(
            "Chapter 3 · 900 words · 4800 characters",
            getString(Res.string.chapter_words_characters, "3", "900", "4800"),
        )
    }
}
