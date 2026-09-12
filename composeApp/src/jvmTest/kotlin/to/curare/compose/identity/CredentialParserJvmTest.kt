package to.curare.compose.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Every row of the sign-in screen's table, in and out.
 *
 * The accepted rows all use NIP-06's own vector, so the three ways of writing one key --
 * twelve words, an nsec, sixty-four hex characters -- are asserted to land on the same
 * public key. That equality is what the duplicate check in `IdentityWriter` rests on.
 */
class CredentialParserJvmTest {

    private val words = "leader monkey parrot ring guide accident before fence cannon height naive bean"
    private val nsec = "nsec10allq0gjx7fddtzef0ax00mdps9t2kmtrldkyjfs8l5xruwvh2dq0lhhkp"
    private val hex = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
    private val publicKey = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"
    private val npub = "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu"

    private fun recognised(input: String): SignInCredential =
        assertIs<CredentialParser.Result.Recognised>(CredentialParser.parse(input), "for '$input'").credential

    private fun refused(input: String): CredentialProblem =
        assertIs<CredentialParser.Result.Refused>(CredentialParser.parse(input), "for '$input'").problem

    @Test
    fun `twelve words are a recovery phrase, and derive the NIP-06 key`() {
        val credential = assertIs<SignInCredential.RecoveryPhrase>(recognised(words))
        assertEquals(words.split(" "), credential.words)
        assertEquals(publicKey, credential.nostrPublicKey)
    }

    @Test
    fun `a phrase pasted with line breaks and extra spaces is the same phrase`() {
        val credential = assertIs<SignInCredential.RecoveryPhrase>(
            recognised("  leader  monkey\nparrot ring\tguide accident before fence cannon height naive bean \n")
        )
        assertEquals(publicKey, credential.nostrPublicKey)
    }

    @Test
    fun `an nsec, a nostr-prefixed nsec and the hex all name the same key`() {
        for (input in listOf(nsec, "nostr:$nsec", hex, nsec.uppercase(), hex.uppercase())) {
            val credential = assertIs<SignInCredential.NostrSecret>(recognised(input), "for '$input'")
            assertEquals(publicKey, credential.nostrPublicKey, "for '$input'")
            assertEquals(hex, credential.privateKey.value.toHex(), "for '$input'")
        }
    }

    @Test
    fun `nothing is nothing`() {
        assertEquals(CredentialProblem.Empty, refused(""))
        assertEquals(CredentialProblem.Empty, refused("   \n "))
    }

    @Test
    fun `a single word that is not a key is not recognised`() {
        assertEquals(CredentialProblem.NotRecognised, refused("hello"))
        assertEquals(CredentialProblem.NotRecognised, refused("nostr:"))
        assertEquals(CredentialProblem.NotRecognised, refused("7f7ff03d")) // hex, but not 64 of it
    }

    @Test
    fun `a wrong word, a missing word or a capitalised word is an invalid phrase`() {
        assertEquals(CredentialProblem.InvalidPhrase, refused(words.replace("bean", "beans")))
        assertEquals(CredentialProblem.InvalidPhrase, refused(words.substringBeforeLast(" ")))
        // The checksum would pass if this were lower-cased; the wordlist is lower-case and
        // a capital is a paste artefact the user should see rather than have silently fixed.
        assertEquals(CredentialProblem.InvalidPhrase, refused(words.replaceFirstChar { it.uppercase() }))
    }

    @Test
    fun `a public key is named for what it is`() {
        assertEquals(CredentialProblem.PublicKeyOnly, refused(npub))
        assertEquals(CredentialProblem.PublicKeyOnly, refused("nostr:$npub"))
    }

    @Test
    fun `an encrypted key is named for what it is`() {
        assertEquals(CredentialProblem.EncryptedKey, refused("ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"))
    }

    @Test
    fun `an nsec that does not decode, or a hex that is not a valid secret, is an invalid key`() {
        assertEquals(CredentialProblem.InvalidKey, refused(nsec.dropLast(1) + "q"))
        assertEquals(CredentialProblem.InvalidKey, refused("0".repeat(64)))
        assertEquals(CredentialProblem.InvalidKey, refused("f".repeat(64)))
    }
}
