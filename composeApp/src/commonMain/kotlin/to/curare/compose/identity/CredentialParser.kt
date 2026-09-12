package to.curare.compose.identity

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.bech32.Bech32
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.managers.nostrPublicKeyHex
import fr.acinq.phoenix.utils.MnemonicLanguage

/**
 * What a user pasted into the sign-in field, once it has been recognised.
 *
 * Both carry the nostr public key, because the confirm step shows it and the duplicate
 * check compares it. Neither prints its secret.
 */
sealed interface SignInCredential {
    val nostrPublicKey: HexKey

    /** Twelve (or more) words: a wallet, and the nostr key derived from it. */
    data class RecoveryPhrase(
        val words: List<String>,
        override val nostrPublicKey: HexKey,
    ) : SignInCredential {
        override fun toString(): String = "RecoveryPhrase(npub=$nostrPublicKey, words=***)"
    }

    /** A bare nostr secret. No wallet comes with it. */
    data class NostrSecret(
        val privateKey: PrivateKey,
        override val nostrPublicKey: HexKey,
    ) : SignInCredential {
        override fun toString(): String = "NostrSecret(npub=$nostrPublicKey, key=<redacted>)"
    }
}

/** Why an input was not accepted. The screen turns each into a sentence. */
enum class CredentialProblem {
    /** Nothing was entered. */
    Empty,

    /** Not words, not an nsec, not hex: nothing this screen knows how to read. */
    NotRecognised,

    /** Words, but the checksum or the wordlist says no: a wrong word, or one out of order. */
    InvalidPhrase,

    /** An nsec or hex that does not decode to a valid secp256k1 secret. */
    InvalidKey,

    /** An npub. A public key cannot sign, and every write path here assumes a key that can. */
    PublicKeyOnly,

    /** An ncryptsec (NIP-49). Decrypting one is a follow-on; say so rather than fail opaquely. */
    EncryptedKey,

    /** An identity with this nostr public key is already on the device, as a wallet or a key. */
    AlreadyOnThisDevice,

    /** The secret could not be written to disk. */
    CouldNotWrite,
}

/**
 * Recognises a pasted credential. Pure, so it can be pinned row by row.
 *
 * A user does not choose an input type; they paste what they have. The two accepted
 * shapes are unambiguous -- words have spaces, keys do not -- and the two refused
 * shapes are named for what they are rather than lumped in with garbage, because a
 * user who pasted an npub or an ncryptsec did something reasonable and should be told
 * what to do instead.
 */
object CredentialParser {

    sealed interface Result {
        data class Recognised(val credential: SignInCredential) : Result
        data class Refused(val problem: CredentialProblem) : Result
    }

    private val hex64 = Regex("[0-9a-f]{64}")

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Refused(CredentialProblem.Empty)

        // Whitespace anywhere inside means words. Not lower-cased: the wordlist already
        // is, and a capitalised word is a paste artefact worth showing rather than fixing.
        val words = text.split(Regex("\\s+"))
        if (words.size > 1) return recoveryPhrase(words)

        // Bech32 is case-insensitive as a whole but rejects mixed case, and a `nostr:`
        // prefix is how a key arrives from a link.
        val token = text.removePrefix("nostr:").lowercase()
        return when {
            token.startsWith("npub1") -> Result.Refused(CredentialProblem.PublicKeyOnly)
            token.startsWith("ncryptsec1") -> Result.Refused(CredentialProblem.EncryptedKey)
            token.startsWith("nsec1") -> nsec(token)
            hex64.matches(token) -> secret(runCatching { PrivateKey.fromHex(token) }.getOrNull())
            else -> Result.Refused(CredentialProblem.NotRecognised)
        }
    }

    private fun recoveryPhrase(words: List<String>): Result = try {
        MnemonicCode.validate(mnemonics = words, wordlist = MnemonicLanguage.English.wordlist())
        Result.Recognised(SignInCredential.RecoveryPhrase(words, StoredIdentity.nostrPublicKeyOf(words)))
    } catch (e: Exception) {
        Result.Refused(CredentialProblem.InvalidPhrase)
    }

    private fun nsec(token: String): Result {
        val bytes = runCatching {
            val (hrp, data) = Bech32.decodeBytes(token)
            require(hrp == "nsec") { "not an nsec" }
            data
        }.getOrNull() ?: return Result.Refused(CredentialProblem.InvalidKey)
        return secret(runCatching { PrivateKey(bytes) }.getOrNull())
    }

    private fun secret(privateKey: PrivateKey?): Result {
        if (privateKey == null || !privateKey.isValid()) return Result.Refused(CredentialProblem.InvalidKey)
        return Result.Recognised(SignInCredential.NostrSecret(privateKey, privateKey.nostrPublicKeyHex()))
    }
}
