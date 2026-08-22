package com.kinex.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

/**
 * [Base64Url] exists because the two library implementations either pad by default or are not
 * available on this `minSdk`, so it is hand-written — which means the tail cases are ours to
 * get right rather than somebody else's. Those are what this covers.
 *
 * The lengths that matter in this system are 32 bytes (a public key, a nonce, a SHA-256
 * digest), 64 (a signature) and 16 (the recovery-phrase entropy). 32 and 16 both leave a
 * two-byte tail, 64 leaves one, so between them the fixed sizes exercise both branches — but
 * only by luck, which is why the exhaustive length sweep below is here too.
 */
class Base64UrlTest {

    @Test
    fun `known vectors, from RFC 4648's test set with the padding removed`() {
        assertEquals("", Base64Url.encode(byteArrayOf()))
        assertEquals("Zg", Base64Url.encode("f".toByteArray()))
        assertEquals("Zm8", Base64Url.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Url.encode("foo".toByteArray()))
        assertEquals("Zm9vYg", Base64Url.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE", Base64Url.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".toByteArray()))
    }

    @Test
    fun `the url alphabet is used, not the standard one`() {
        // 0xfb 0xff 0xbf encodes to the two characters that differ between the alphabets.
        val raw = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte())
        assertEquals("-_-_", Base64Url.encode(raw))
        assertArrayEquals(raw, Base64Url.decode("-_-_"))
    }

    @Test
    fun `every length from 0 to 200 round-trips and never pads`() {
        val random = Random(20260822)
        for (size in 0..200) {
            val raw = random.nextBytes(size)
            val encoded = Base64Url.encode(raw)
            assertEquals("padding at size $size: $encoded", -1, encoded.indexOf('='))
            assertArrayEquals("round trip failed at size $size", raw, Base64Url.decode(encoded))
        }
    }

    @Test
    fun `all 256 byte values survive, so no sign extension is hiding in the shifts`() {
        val raw = ByteArray(256) { it.toByte() }
        assertArrayEquals(raw, Base64Url.decode(Base64Url.encode(raw)))
    }

    @Test
    fun `a character outside the alphabet is refused`() {
        // '+' and '/' are the standard alphabet's, and are exactly what a server that padded
        // and used the wrong alphabet would send. Refusing them is how that gets noticed.
        assertThrows(IllegalArgumentException::class.java) { Base64Url.decode("Zm9vYmFy+") }
        assertThrows(IllegalArgumentException::class.java) { Base64Url.decode("Zm9vYmFy/") }
        assertThrows(IllegalArgumentException::class.java) { Base64Url.decode("Zm9v=") }
    }

    @Test
    fun `a length no byte array could produce is refused`() {
        // 4n+1 characters is six bits of payload. Nothing encodes to that.
        assertThrows(IllegalArgumentException::class.java) { Base64Url.decode("Z") }
        assertThrows(IllegalArgumentException::class.java) { Base64Url.decode("Zm9vY") }
    }

    @Test
    fun `decoding to a fixed size refuses the wrong size`() {
        assertEquals(32, Base64Url.decode(Base64Url.encode(ByteArray(32)), 32).size)
        assertThrows(IllegalArgumentException::class.java) {
            Base64Url.decode(Base64Url.encode(ByteArray(31)), 32)
        }
    }
}
