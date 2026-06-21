package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationParserTest {
    @Test
    fun `parses Google Authenticator version 2 payload`() {
        val batch = MigrationParser.parse(
            "otpauth-migration://offline?data=" +
                "Ci0KCg%2FYQQwg9yXtGdwSBFRlc3QgASgBMAJCEzE2ODcyMDY0MDgxMzI3MkY5MzcQAhgBIAA%3D"
        )

        assertEquals(1, batch.batchSize)
        assertEquals(0, batch.batchIndex)
        assertEquals(0L, batch.batchId)
        assertEquals(1, batch.entries.size)
        with(batch.entries.single()) {
            assertEquals("Test", displayName)
            assertEquals("Test", accountName)
            assertEquals(TotpAlgorithm.SHA1, algorithm)
            assertEquals(6, digits)
            assertArrayEquals(
                byteArrayOf(0x0f, 0xd8.toByte(), 0x41, 0x0c, 0x20, 0xf7.toByte(), 0x25, 0xed.toByte(), 0x19, 0xdc.toByte()),
                secret,
            )
        }
    }
}
