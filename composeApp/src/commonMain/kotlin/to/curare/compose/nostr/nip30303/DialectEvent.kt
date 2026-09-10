package to.curare.compose.nostr.nip30303

import androidx.compose.runtime.Immutable
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.TagArrayBuilder
import com.vitorpamplona.quartz.nip01Core.signers.eventTemplate
import com.vitorpamplona.quartz.nip22Comments.RootScope
import com.vitorpamplona.quartz.nip31Alts.alt
import com.vitorpamplona.quartz.nip50Search.SearchableEvent
import com.vitorpamplona.quartz.utils.TimeUtils
import to.curare.compose.nostr.nip30303.tags.CountryTag
import to.curare.compose.nostr.nip30303.tags.LanguageTag
import to.curare.compose.nostr.nip30303.tags.NameTag


@Immutable
class DialectEvent(
    id: HexKey,
    pubKey: HexKey,
    createdAt: Long,
    tags: Array<Array<String>>,
    content: String,
    sig: HexKey,
) : Event(id, pubKey, createdAt, KIND, tags, content, sig) {
    fun name() = tags.firstNotNullOfOrNull(NameTag::parse)?.name
    fun country() = tags.firstNotNullOfOrNull(CountryTag::parse)?.country
    fun language() = tags.firstNotNullOfOrNull(LanguageTag::parse)?.language

    companion object {
        const val KIND = 30304
        const val ALT_DESCRIPTION = "Dialect"

        fun build(
            name: String,
            country: String,
            language: String,
            createdAt: Long = TimeUtils.now(),
            initializer: TagArrayBuilder<DialectEvent>.() -> Unit = {},
        ) = eventTemplate(KIND, language, createdAt) {
            alt(ALT_DESCRIPTION)
            addUnique(NameTag.assemble(name))
            addUnique(CountryTag.assemble(country))
            addUnique(LanguageTag.assemble(language))
            initializer()
        }
    }
}
