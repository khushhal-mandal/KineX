package com.kinex.sync

/**
 * base64url, unpadded, in both directions.
 *
 * **Hand-written rather than delegated, for three reasons that all point the same way.**
 *
 * `java.util.Base64` is API 26 and `minSdk` is 24, so it is not available at all.
 * `android.util.Base64` is available but is one of the `android.jar` classes that local unit
 * tests stub out, and the whole point of this file's neighbours is that the auth contract can
 * be checked against `backend/tests/vectors/auth_v1.json` without a device attached — one
 * Android import anywhere in the chain and that check has to become an instrumented test.
 *
 * The third reason is the important one. The root design doc names a padding mismatch as the
 * most likely way the auth contract gets broken, and says why it is nasty: it fails as an
 * opaque 401 with nothing to inspect. `android.util.Base64` pads unless told `NO_PADDING` and
 * Python's `urlsafe_b64encode` pads unconditionally, so on both sides the default is wrong and
 * the correct behaviour is a flag somebody has to remember. Forty lines that cannot emit a `=`
 * at all removes the failure mode rather than guarding against it.
 *
 * Not a general-purpose codec: there is no standard-alphabet mode and no padding support,
 * because nothing here wants either.
 */
internal object Base64Url {

    /** RFC 4648 §5: standard base64 with `-` and `_` in place of `+` and `/`. */
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(raw: ByteArray): String {
        val out = StringBuilder((raw.size * 4 + 2) / 3)
        var i = 0
        // Whole three-byte groups first; each becomes exactly four characters.
        while (i + 2 < raw.size) {
            val group = ((raw[i].toInt() and 0xff) shl 16) or
                ((raw[i + 1].toInt() and 0xff) shl 8) or
                (raw[i + 2].toInt() and 0xff)
            out.append(ALPHABET[(group ushr 18) and 0x3f])
            out.append(ALPHABET[(group ushr 12) and 0x3f])
            out.append(ALPHABET[(group ushr 6) and 0x3f])
            out.append(ALPHABET[group and 0x3f])
            i += 3
        }
        // The tail, which is where padding would have gone. One leftover byte carries 8 bits
        // and needs two characters; two leftover bytes carry 16 and need three.
        when (raw.size - i) {
            1 -> {
                val group = (raw[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[(group ushr 18) and 0x3f])
                out.append(ALPHABET[(group ushr 12) and 0x3f])
            }

            2 -> {
                val group = ((raw[i].toInt() and 0xff) shl 16) or
                    ((raw[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[(group ushr 18) and 0x3f])
                out.append(ALPHABET[(group ushr 12) and 0x3f])
                out.append(ALPHABET[(group ushr 6) and 0x3f])
            }
        }
        return out.toString()
    }

    /**
     * Strict: anything outside the base64url alphabet is rejected rather than skipped, and a
     * length that no byte array could have produced is rejected too.
     *
     * Lenient decoding is how two spellings of one key become two devices — the same argument
     * the server's `b64u_decode` makes for passing `validate=True`.
     */
    fun decode(text: String): ByteArray {
        // 4n+1 characters is 6 bits of payload, which no whole number of bytes encodes to.
        require(text.length % 4 != 1) { "not valid unpadded base64url: bad length" }
        val out = ByteArray(text.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        var written = 0
        for (character in text) {
            val value = ALPHABET.indexOf(character)
            require(value >= 0) { "not valid unpadded base64url" }
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((accumulator ushr bits) and 0xff).toByte()
            }
        }
        return out
    }

    /** [decode], insisting on a known length. Used wherever the contract fixes one. */
    fun decode(text: String, expectedBytes: Int): ByteArray {
        val raw = decode(text)
        require(raw.size == expectedBytes) {
            "expected $expectedBytes bytes, got ${raw.size}"
        }
        return raw
    }
}
