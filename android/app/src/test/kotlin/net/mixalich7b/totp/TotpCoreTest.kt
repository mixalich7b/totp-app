package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TotpCoreTest {
    @Test
    fun `base32 decodes RFC example`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW 6-YTB-OI======"))
    }

    @Test
    fun `base32 rejects non-zero trailing bits`() {
        assertThrows(IllegalArgumentException::class.java) { Base32.decode("MZ") }
    }

    @Test
    fun `base32 accepts valid padded and unpadded values`() {
        assertArrayEquals("f".toByteArray(), Base32.decode("MY"))
        assertArrayEquals("f".toByteArray(), Base32.decode("MY======"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI"))
    }

    @Test
    fun `base32 rejects malformed length padding and alphabet`() {
        listOf("M", "MZX", "MY=====", "MY=======", "MY===A==", "M0").forEach { value ->
            assertThrows(value, IllegalArgumentException::class.java) { Base32.decode(value) }
        }
    }

    @Test
    fun `RFC 6238 SHA1 vectors`() {
        val entry = entry("12345678901234567890".toByteArray(), TotpAlgorithm.SHA1, 8)
        assertEquals("94287082", Totp.generate(entry, 59))
        assertEquals("07081804", Totp.generate(entry, 1_111_111_109))
        assertEquals("89005924", Totp.generate(entry, 1_234_567_890))
        assertEquals("69279037", Totp.generate(entry, 2_000_000_000))
    }

    @Test
    fun `RFC 6238 SHA256 vectors`() {
        val entry = entry("12345678901234567890123456789012".toByteArray(), TotpAlgorithm.SHA256, 8)
        assertEquals("46119246", Totp.generate(entry, 59))
        assertEquals("68084774", Totp.generate(entry, 1_111_111_109))
        assertEquals("91819424", Totp.generate(entry, 1_234_567_890))
    }

    @Test
    fun `TOTP changes only at period boundary and keeps leading zeroes`() {
        val entry = entry("12345678901234567890".toByteArray(), TotpAlgorithm.SHA1, 8)
        assertEquals("94287082", Totp.generate(entry, 30))
        assertEquals("94287082", Totp.generate(entry, 59))
        assertNotEquals(Totp.generate(entry, 59), Totp.generate(entry, 60))
        assertEquals("07081804", Totp.generate(entry, 1_111_111_109))
    }

    @Test
    fun `TOTP supports timestamps after 2038`() {
        val entry = entry("12345678901234567890".toByteArray(), TotpAlgorithm.SHA1, 8)
        assertEquals(8, Totp.generate(entry, 4_102_444_800L).length)
    }

    @Test
    fun `otpauth parser reads parameters`() {
        val value = OtpAuthParser.parse(
            "otpauth://totp/Example:alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example&algorithm=SHA256&digits=8&period=60"
        )
        assertEquals("Example: alice@example.com", value.displayName)
        assertEquals(TotpAlgorithm.SHA256, value.algorithm)
        assertEquals(8, value.digits)
        assertEquals(60, value.periodSeconds)
    }

    @Test
    fun `HOTP URI is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            OtpAuthParser.parse("otpauth://hotp/test?secret=JBSWY3DPEHPK3PXP&counter=1")
        }
    }

    @Test
    fun `otpauth parser percent decodes exactly once and preserves plus`() {
        val value = OtpAuthParser.parse(
            "otpauth://totp/Example:alice%2540mail%2Btag?secret=JBSWY3DPEHPK3PXP&issuer=Example"
        )
        assertEquals("alice%40mail+tag", value.accountName)
    }

    @Test
    fun `otpauth parser handles Unicode and missing issuer`() {
        val value = OtpAuthParser.parse(
            "otpauth://totp/%D0%90%D0%BB%D0%B8%D1%81%D0%B0?secret=JBSWY3DPEHPK3PXP"
        )
        assertEquals("Алиса", value.displayName)
        assertEquals("", value.issuer)
        assertEquals("Алиса", value.accountName)
    }

    @Test
    fun `otpauth parser rejects mismatched issuers`() {
        assertThrows(IllegalArgumentException::class.java) {
            OtpAuthParser.parse(
                "otpauth://totp/First:alice?secret=JBSWY3DPEHPK3PXP&issuer=Second"
            )
        }
    }

    @Test
    fun `otpauth parser rejects unsupported parameters`() {
        listOf(
            "otpauth://totp/test?secret=JBSWY3DPEHPK3PXP&algorithm=SHA512",
            "otpauth://totp/test?secret=JBSWY3DPEHPK3PXP&digits=7",
            "otpauth://totp/test?secret=JBSWY3DPEHPK3PXP&period=301",
            "otpauth://totp/test?secret=INVALID0",
        ).forEach { uri ->
            assertThrows(uri, IllegalArgumentException::class.java) { OtpAuthParser.parse(uri) }
        }
    }

    private fun entry(secret: ByteArray, algorithm: TotpAlgorithm, digits: Int) = TotpEntry(
        displayName = "test", issuer = "", accountName = "", secret = secret,
        algorithm = algorithm, digits = digits,
    )
}
