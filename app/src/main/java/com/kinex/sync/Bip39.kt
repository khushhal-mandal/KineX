package com.kinex.sync

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.text.Normalizer

/**
 * BIP-39, English, 12 words — entropy to phrase, phrase back to entropy, phrase to seed.
 *
 * Exactly the three steps the root design doc's auth contract specifies and no more. There is
 * no 15/18/24-word support and no non-English wordlist, because nothing asks for either.
 *
 * **No Android imports, deliberately.** This class and its two neighbours are what
 * `AuthVectorTest` checks against `backend/tests/vectors/auth_v1.json`, and that test is a
 * plain JVM unit test. It stays one only as long as nothing here reaches for `android.*` — so
 * if a future edit adds an import that needs a device, the vector check breaks loudly rather
 * than quietly stopping being run.
 *
 * The wordlist is a **Java resource**, not an asset, for the same reason: an asset needs a
 * `Context` to read, a resource does not. It is the canonical BIP-39 English list, SHA-256
 * `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`, which is the checksum
 * published alongside the wordlist itself.
 */
internal object Bip39 {

    /** 128 bits, as the contract specifies. 12 words is what that produces, not a second knob. */
    const val ENTROPY_BYTES = 16
    const val WORD_COUNT = 12

    private const val BITS_PER_WORD = 11
    private const val ITERATIONS = 2048
    private const val SEED_BYTES = 64

    /**
     * The BIP-39 salt is `"mnemonic"` concatenated with the passphrase. The contract fixes the
     * passphrase as empty, so in practice the salt is exactly these eight characters — which is
     * worth having spelled out, because "the salt is the passphrase" is the natural misreading
     * and it derives a different key in silence.
     */
    private const val SALT_PREFIX = "mnemonic"

    private val wordlist: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("/bip39_english.txt")
            ?: error("bip39_english.txt is not on the classpath")
        val words = stream.bufferedReader().useLines { lines ->
            lines.map(String::trim).filter(String::isNotEmpty).toList()
        }
        check(words.size == 2048) { "wordlist has ${words.size} words, expected 2048" }
        words
    }

    /**
     * Splits a phrase the way a human might have typed it — any run of whitespace, any case.
     *
     * Tolerated here rather than at every call site because the alternative is a restore that
     * fails on a trailing space, with the only symptom a checksum error the user cannot act on.
     */
    fun words(phrase: String): List<String> =
        phrase.trim().lowercase().split(WHITESPACE).filter(String::isNotEmpty)

    /** The phrase as it is stored and displayed: lowercase, single-spaced. */
    fun canonical(phrase: String): String = words(phrase).joinToString(" ")

    /**
     * Entropy to phrase.
     *
     * The 128 entropy bits are followed by the first 4 bits of their own SHA-256, giving 132
     * bits — which is 12 × 11, and each 11-bit group indexes the wordlist. The checksum is
     * what makes a mistyped word detectable instead of silently deriving somebody else's key.
     */
    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) {
            "expected $ENTROPY_BYTES bytes of entropy, got ${entropy.size}"
        }
        val entropyBits = entropy.size * 8
        val checksumBits = entropyBits / 32
        val checksum = sha256(entropy)

        return (0 until (entropyBits + checksumBits) / BITS_PER_WORD).map { word ->
            var index = 0
            for (offset in 0 until BITS_PER_WORD) {
                val position = word * BITS_PER_WORD + offset
                val bit = if (position < entropyBits) {
                    bitAt(entropy, position)
                } else {
                    bitAt(checksum, position - entropyBits)
                }
                index = (index shl 1) or bit
            }
            wordlist[index]
        }
    }

    /**
     * Phrase back to entropy, refusing anything whose checksum does not agree.
     *
     * Throws [IllegalArgumentException] with a message meant to be shown to whoever is typing:
     * an unknown word names the word, a bad checksum says the phrase is wrong rather than which
     * part of it is, because the checksum genuinely cannot tell.
     */
    fun decode(phrase: String): ByteArray {
        val typed = words(phrase)
        require(typed.size == WORD_COUNT) {
            "a recovery phrase is $WORD_COUNT words; this one has ${typed.size}"
        }
        val indices = typed.map { word ->
            wordlist.indexOf(word).also {
                require(it >= 0) { "\"$word\" is not a word in the BIP-39 English list" }
            }
        }

        val totalBits = WORD_COUNT * BITS_PER_WORD
        val entropyBits = totalBits * 32 / 33
        val checksumBits = totalBits - entropyBits
        val entropy = ByteArray(entropyBits / 8)
        var carried = 0
        for (position in 0 until totalBits) {
            val index = indices[position / BITS_PER_WORD]
            val bit = (index shr (BITS_PER_WORD - 1 - position % BITS_PER_WORD)) and 1
            if (position < entropyBits) {
                if (bit == 1) {
                    val byte = position / 8
                    entropy[byte] = (entropy[byte].toInt() or (1 shl (7 - position % 8))).toByte()
                }
            } else {
                carried = (carried shl 1) or bit
            }
        }

        val digest = sha256(entropy)
        var expected = 0
        for (offset in 0 until checksumBits) expected = (expected shl 1) or bitAt(digest, offset)
        require(expected == carried) { "that recovery phrase is not valid — check the words" }

        return entropy
    }

    /**
     * Phrase to the 64-byte BIP-39 seed.
     *
     * PBKDF2-HMAC-SHA512, 2048 iterations, both inputs NFKD-normalised. The English wordlist is
     * pure ASCII so NFKD changes nothing for us today; it is here because it is what the spec
     * says, and the day it starts mattering is not a day anyone will be looking at this file.
     *
     * BouncyCastle's lightweight generator rather than
     * `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")`: that algorithm name only exists
     * on the platform provider from API 26, and `minSdk` is 24.
     */
    fun seed(phrase: String, passphrase: String = ""): ByteArray {
        val normalisedPhrase = normalise(canonical(phrase))
        val salt = normalise(SALT_PREFIX + passphrase)
        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(
            normalisedPhrase.toByteArray(Charsets.UTF_8),
            salt.toByteArray(Charsets.UTF_8),
            ITERATIONS,
        )
        return (generator.generateDerivedParameters(SEED_BYTES * 8) as KeyParameter).key
    }

    private fun normalise(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    /** Bit [position] of [source], counting from the most significant bit of byte zero. */
    private fun bitAt(source: ByteArray, position: Int): Int =
        (source[position / 8].toInt() shr (7 - position % 8)) and 1

    private val WHITESPACE = Regex("\\s+")
}
