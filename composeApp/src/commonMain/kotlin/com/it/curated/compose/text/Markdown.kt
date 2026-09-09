package com.it.curated.compose.text

/**
 * Lightweight helpers for the markdown original text of chapters/chunks.
 *
 * Counts are computed on the raw markdown text (no rendering/stripping):
 * words are whitespace-separated tokens and characters are the string length.
 */
object Markdown {
    private val WHITESPACE = Regex("\\s+")

    /** A paragraph break is one or more blank lines. */
    private val PARAGRAPH_BREAK = Regex("(?:\\r?\\n){2,}")

    fun wordCount(text: String): Int {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) 0 else trimmed.split(WHITESPACE).count { it.isNotBlank() }
    }

    fun characterCount(text: String): Int = text.length

    /**
     * Split markdown into paragraphs (blocks separated by blank lines),
     * trimmed and with empties removed. Each paragraph becomes a chunk.
     */
    fun splitParagraphs(text: String): List<String> =
        text.split(PARAGRAPH_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
