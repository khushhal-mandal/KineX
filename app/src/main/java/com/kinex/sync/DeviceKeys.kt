package com.kinex.sync

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/**
 * The device's Ed25519 keypair, and the two things the server ever sees from it: the public
 * key, and a signature over a challenge.
 *
 * This is the Kotlin half of "The auth contract" in the root design doc, and the contract is a
 * byte-level specification precisely because both halves have to agree exactly. Four things
 * here are the ones that get got wrong, so each is stated where it happens rather than only in
 * the prose:
 *
 * 1. **The key comes from `seed[0:32]`** — the first half of BIP-39's 64-byte seed, with no
 *    BIP-32 or SLIP-10 derivation path applied. Reaching for `m/44'/...` is the reasonable
 *    assumption and derives a different key.
 * 2. **Raw bytes, never DER or PEM.** 32 for the public key, 64 for a signature.
 * 3. **The signed message is `"kinex-auth-v1:" + nonce`, with the nonce in its base64url text
 *    form** — not decoded first. Signing the bare nonce is refused by the server and there is a
 *    test there that expects a 401 for it.
 * 4. **`device_id` is derived, not random**, so re-registering the same key is the same
 *    account. That is what makes a recovery-phrase restore land on the existing history.
 *
 * No Android imports — see the note on [Bip39] for why that is load-bearing.
 */
internal class DeviceKeys private constructor(
    private val privateKey: Ed25519PrivateKeyParameters,
) {

    /** Raw 32 bytes, base64url, unpadded. What `POST /auth/challenge` is keyed on. */
    val publicKey: String = Base64Url.encode(privateKey.generatePublicKey().encoded)

    /**
     * `base64url(sha256(raw public key))`, unpadded — 43 characters.
     *
     * The device does not have to compute this: the token response returns it, and that is the
     * value everything downstream uses. It is derived here anyway so the vector can be checked
     * without a server, which is the only reason this property exists.
     */
    val deviceId: String =
        Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(rawPublicKey()))

    private fun rawPublicKey(): ByteArray = privateKey.generatePublicKey().encoded

    /**
     * Signs a challenge nonce, returning the 64-byte signature base64url-encoded.
     *
     * [nonce] is the server's nonce exactly as it arrived — still base64url text. It is
     * concatenated, not decoded: the whole signed message is then printable ASCII, so a
     * mismatch between the two implementations shows up in a log line rather than as a hex
     * diff. The prefix is domain separation, and it is not decoration — the server chooses the
     * nonce, and signing bare server-chosen bytes would turn this device into a signing oracle.
     */
    fun signChallenge(nonce: String): String {
        val message = (AUTH_DOMAIN + nonce).toByteArray(Charsets.US_ASCII)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return Base64Url.encode(signer.generateSignature())
    }

    companion object {
        /** Changing this string is how the message format gets versioned. Both sides must agree. */
        const val AUTH_DOMAIN = "kinex-auth-v1:"

        private const val PRIVATE_KEY_BYTES = 32

        /**
         * From BIP-39's 64-byte seed. Takes the first 32 bytes and nothing else — the slice is
         * written out rather than passed as an offset so that `seed[0:32]` is legible as code.
         */
        fun fromSeed(seed: ByteArray): DeviceKeys {
            require(seed.size >= PRIVATE_KEY_BYTES) {
                "a seed is at least $PRIVATE_KEY_BYTES bytes; got ${seed.size}"
            }
            val material = seed.copyOfRange(0, PRIVATE_KEY_BYTES)
            return DeviceKeys(Ed25519PrivateKeyParameters(material))
        }

        /** The whole derivation, phrase in and keypair out, in the order the contract states it. */
        fun fromPhrase(phrase: String): DeviceKeys = fromSeed(Bip39.seed(phrase))
    }
}
