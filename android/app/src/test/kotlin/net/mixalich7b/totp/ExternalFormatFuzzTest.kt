package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.random.Random

class ExternalFormatFuzzTest {
    @Test
    fun `base32 round trips deterministic random byte arrays with separators`() {
        val random = Random(0x32B17)
        repeat(500) { iteration ->
            val expected = random.nextBytes(random.nextInt(1, TotpEntry.MAX_SECRET_BYTES + 1))
            val encoded = base32Encode(expected)
            val decorated = buildString {
                encoded.forEachIndexed { index, character ->
                    if (index > 0 && index % 11 == 0) append(if (index % 22 == 0) '-' else ' ')
                    append(if (iteration % 2 == 0) character.lowercaseChar() else character)
                }
            }

            assertArrayEquals("iteration=$iteration", expected, Base32.decode(decorated))
        }
    }

    @Test
    fun `base32 rejects invalid mutations and oversized secrets`() {
        val random = Random(0xBAD32)
        repeat(200) {
            val encoded = base32Encode(random.nextBytes(random.nextInt(1, 128)))
            val index = random.nextInt(encoded.length)
            val mutated = encoded.replaceRange(index, index + 1, "0")
            assertThrows(mutated, IllegalArgumentException::class.java) { Base32.decode(mutated) }
        }

        assertThrows(IllegalArgumentException::class.java) {
            TotpEntry(displayName = "oversized", issuer = "", accountName = "", secret = ByteArray(1025))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Base32.decode("A".repeat(1640))
        }
    }

    @Test
    fun `otpauth parser round trips percent encoded Unicode labels`() {
        val random = Random(0x07A07)
        repeat(250) { iteration ->
            val issuer = randomText(random, "Issuer")
            val account = randomText(random, "Account")
            val secret = random.nextBytes(random.nextInt(1, 65))
            val algorithm = if (random.nextBoolean()) TotpAlgorithm.SHA1 else TotpAlgorithm.SHA256
            val digits = if (random.nextBoolean()) 6 else 8
            val period = random.nextInt(5, 301)
            val uri = "otpauth://totp/${percentEncode("$issuer:$account")}" +
                "?secret=${base32Encode(secret)}&issuer=${percentEncode(issuer)}" +
                "&algorithm=${algorithm.name}&digits=$digits&period=$period"

            val parsed = OtpAuthParser.parse(uri)

            assertEquals("iteration=$iteration", issuer, parsed.issuer)
            assertEquals("iteration=$iteration", account, parsed.accountName)
            assertArrayEquals("iteration=$iteration", secret, parsed.secret)
            assertEquals(algorithm, parsed.algorithm)
            assertEquals(digits, parsed.digits)
            assertEquals(period, parsed.periodSeconds)
        }
    }

    @Test
    fun `migration parser rejects or safely accepts arbitrary bounded protobuf`() {
        val random = Random(0xB07B0F)
        repeat(1_000) { iteration ->
            val payload = random.nextBytes(random.nextInt(0, 513))
            assertMigrationOutcome(iteration, migrationUri(payload))
        }
    }

    @Test
    fun `migration parser handles every truncation of a valid payload`() {
        val encoded = "Ci0KCg%2FYQQwg9yXtGdwSBFRlc3QgASgBMAJCEzE2ODcyMDY0MDgxMzI3MkY5MzcQAhgBIAA%3D"
        val payload = Base64.getDecoder().decode(java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()))

        for (length in 0..payload.size) {
            assertMigrationOutcome(length, migrationUri(payload.copyOf(length)))
        }
    }

    private fun assertMigrationOutcome(caseNumber: Int, uri: String) {
        try {
            val batch = MigrationParser.parse(uri)
            assertTrue("case=$caseNumber", batch.entries.isNotEmpty())
            assertTrue("case=$caseNumber", batch.batchSize in 1..100)
            assertTrue("case=$caseNumber", batch.batchIndex in 0 until batch.batchSize)
            batch.entries.forEach { entry ->
                assertTrue("case=$caseNumber", entry.secret.size in 1..TotpEntry.MAX_SECRET_BYTES)
                assertTrue("case=$caseNumber", entry.digits == 6 || entry.digits == 8)
                assertTrue("case=$caseNumber", entry.periodSeconds in 5..300)
                entry.secret.fill(0)
            }
        } catch (_: IllegalArgumentException) {
            // Expected for malformed arbitrary payloads.
        }
    }

    private fun migrationUri(payload: ByteArray): String =
        "otpauth-migration://offline?data=" + URLEncoder.encode(
            Base64.getEncoder().encodeToString(payload),
            StandardCharsets.UTF_8.name(),
        )

    private fun randomText(random: Random, prefix: String): String {
        val alphabet = listOf("a", "Z", "7", "Б", "я", "界", "😀", "+", "%", "@", "-", "_", " ")
        return buildString {
            append(prefix)
            repeat(random.nextInt(1, 16)) { append(alphabet[random.nextInt(alphabet.size)]) }
            append('x')
        }
    }

    private fun percentEncode(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if ((unsigned in 'a'.code..'z'.code) || (unsigned in 'A'.code..'Z'.code) ||
                (unsigned in '0'.code..'9'.code) || unsigned == '-'.code ||
                unsigned == '_'.code || unsigned == '.'.code || unsigned == '~'.code
            ) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private fun base32Encode(value: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        value.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(BASE32_ALPHABET[(buffer shr bits) and 31])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    companion object {
        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val HEX = "0123456789ABCDEF"
    }
}
