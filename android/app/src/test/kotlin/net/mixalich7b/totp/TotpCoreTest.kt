package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private fun entry(secret: ByteArray, algorithm: TotpAlgorithm, digits: Int) = TotpEntry(
        displayName = "test", issuer = "", accountName = "", secret = secret,
        algorithm = algorithm, digits = digits,
    )
}

