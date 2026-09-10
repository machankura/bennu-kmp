package to.curare.compose.nostr.nip30303.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

class WordStatisticsTag(
    val wordCount: Int,
    val characterCount: Int,
) {
    fun toTagArray() = assemble(
        wordCount = wordCount,
        characterCount = characterCount,
    )

    companion object {
        const val TAG_NAME = "word_stats"

        fun parse(tag: Array<String>): WordStatisticsTag? {
            ensure(tag.has(2)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val wordCount = tag[1].toIntOrNull() ?: return null
            val characterCount = tag[2].toIntOrNull() ?: return null

            return WordStatisticsTag(
                wordCount = wordCount,
                characterCount = characterCount,
            )
        }

        fun assemble(
            wordCount: Int,
            characterCount: Int,
        ): Array<String> = arrayOf(
            TAG_NAME,
            wordCount.toString(),
            characterCount.toString(),
        )

        fun assemble(wordStatisticsTag: WordStatisticsTag) = assemble(
            wordCount = wordStatisticsTag.wordCount,
            characterCount = wordStatisticsTag.characterCount,
        )
    }
}