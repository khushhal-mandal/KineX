package com.kinex.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * The auth contract, checked against `backend/tests/vectors/auth_v1.json`.
 *
 * **This is the test that matters in this package.** Every other failure mode in the sync path
 * announces itself — a wrong URL is a connection error, a wrong field name is a 422 naming the
 * field. A key derived one step differently from the server's expectation is a 401 with an
 * empty body, and no amount of staring at either implementation distinguishes "the signature is
 * wrong" from "the key is wrong" from "the message is wrong". The vector is the only thing that
 * says which.
 *
 * Each assertion below is one line of the contract, checked separately rather than end to end,
 * so a break says which step moved. An end-to-end check would only ever report "the signature
 * differs", which is true of a wrong PBKDF2 salt and a wrong domain prefix alike.
 *
 * The file is read from the path Gradle passes in `kinex.authVector` — the real committed file,
 * not a copy. See `testOptions` in `app/build.gradle.kts` for why.
 */
class AuthVectorTest {

    private val vector: JsonObject by lazy {
        val path = checkNotNull(System.getProperty("kinex.authVector")) {
            "kinex.authVector was not set; see testOptions in app/build.gradle.kts"
        }
        val file = File(path)
        check(file.exists()) { "the committed auth vector is not at $path" }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun field(name: String): String = vector.getValue(name).jsonPrimitive.content

    private val phrase get() = field("recovery_phrase")

    @Test
    fun `the vector is the contract version this code implements`() {
        // Guards the one change that would invalidate every other assertion here without
        // failing any of them: a v2 vector checked against a v1 implementation.
        assertEquals("kinex-auth-v1", field("contract_version"))
        assertEquals("kinex-auth-v1:", DeviceKeys.AUTH_DOMAIN)
    }

    @Test
    fun `the phrase derives the published BIP-39 seed`() {
        // The vector's phrase is the canonical all-zero-entropy one precisely so this figure
        // can be checked against BIP-39's own published test vector rather than against us.
        assertEquals(field("bip39_seed_hex"), Bip39.seed(phrase).toHex())
    }

    @Test
    fun `the passphrase is empty, so the salt is exactly mnemonic`() {
        // Stated as a test because "the salt is the passphrase" is the natural misreading, and
        // it derives a different seed in complete silence.
        assertEquals(Bip39.seed(phrase).toHex(), Bip39.seed(phrase, passphrase = "").toHex())
        assertNotEquals(Bip39.seed(phrase).toHex(), Bip39.seed(phrase, "mnemonic").toHex())
    }

    @Test
    fun `the Ed25519 key is the first 32 bytes of the seed, with no derivation path`() {
        val seed = Bip39.seed(phrase)
        assertEquals(64, seed.size)
        assertEquals(field("ed25519_seed_hex"), seed.copyOfRange(0, 32).toHex())
        // Spelled out because it is the assumption that would be made if it were not: the
        // second half of the seed is unused, not hashed in, not a chain code.
        assertEquals(field("bip39_seed_hex").take(64), field("ed25519_seed_hex"))
    }

    @Test
    fun `the public key matches the vector`() {
        assertEquals(field("public_key_b64url"), DeviceKeys.fromPhrase(phrase).publicKey)
    }

    @Test
    fun `the device id matches the vector`() {
        val deviceId = DeviceKeys.fromPhrase(phrase).deviceId
        assertEquals(field("device_id"), deviceId)
        // 43 characters is what an unpadded base64url SHA-256 digest is. A 44th would be
        // padding, which is the failure this contract is most likely to hit.
        assertEquals(43, deviceId.length)
    }

    @Test
    fun `the signed message is the prefix and the nonce in its text form`() {
        assertEquals(
            field("signed_message_ascii"),
            DeviceKeys.AUTH_DOMAIN + field("nonce_b64url"),
        )
    }

    @Test
    fun `the signature matches the vector`() {
        assertEquals(
            field("signature_b64url"),
            DeviceKeys.fromPhrase(phrase).signChallenge(field("nonce_b64url")),
        )
    }

    @Test
    fun `nothing this contract encodes carries padding`() {
        val keys = DeviceKeys.fromPhrase(phrase)
        listOf(keys.publicKey, keys.deviceId, keys.signChallenge(field("nonce_b64url")))
            .forEach { assertFalse("padded: $it", it.contains('=')) }
    }

    @Test
    fun `the all-zero entropy produces the vector's phrase, and round-trips`() {
        // The other direction, which the vector does not state but implies: this is the
        // canonical all-zero-entropy phrase, so generating from 16 zero bytes must produce it.
        val zeros = ByteArray(Bip39.ENTROPY_BYTES)
        assertEquals(phrase, Bip39.encode(zeros).joinToString(" "))
        assertEquals(zeros.toHex(), Bip39.decode(phrase).toHex())
    }

    @Test
    fun `a phrase with a wrong word is refused rather than deriving another key`() {
        val broken = phrase.replaceFirst("about", "abandon")
        val failure = runCatching { Bip39.decode(broken) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, failure?.javaClass)
    }

    @Test
    fun `case and spacing do not change the derived key`() {
        val messy = "  " + phrase.uppercase().replace(" ", "   ") + "\n"
        assertEquals(
            DeviceKeys.fromPhrase(phrase).publicKey,
            DeviceKeys.fromPhrase(messy).publicKey,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
