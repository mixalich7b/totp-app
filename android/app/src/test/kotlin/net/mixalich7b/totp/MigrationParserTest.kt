package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

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

    @Test
    fun `parses Unicode SHA256 entry without issuer`() {
        val entry = protoMessage(
            bytesField(1, byteArrayOf(1, 2, 3, 4)),
            stringField(2, "Сервис: пользователь@example.com"),
            varintField(4, 2),
            varintField(5, 2),
            varintField(6, 2),
        )
        val batch = MigrationParser.parse(migrationUri(payload(entry, version = 1)))

        with(batch.entries.single()) {
            assertEquals("Сервис: пользователь@example.com", displayName)
            assertEquals("Сервис", issuer)
            assertEquals("пользователь@example.com", accountName)
            assertEquals(TotpAlgorithm.SHA256, algorithm)
            assertEquals(8, digits)
        }
    }

    @Test
    fun `retains out of order batch metadata`() {
        val entry = basicEntry("second")
        val second = MigrationParser.parse(migrationUri(payload(entry, batchSize = 2, batchIndex = 1, batchId = 42)))
        val first = MigrationParser.parse(migrationUri(payload(basicEntry("first"), batchSize = 2, batchIndex = 0, batchId = 42)))

        assertEquals(1, second.batchIndex)
        assertEquals("second", second.entries.single().displayName)
        assertEquals(0, first.batchIndex)
        assertEquals("first", first.entries.single().displayName)
        assertEquals(42L, first.batchId)
    }

    @Test
    fun `skips unknown protobuf fields`() {
        val root = protoMessage(
            bytesField(1, basicEntry("known")),
            bytesField(99, byteArrayOf(9, 8, 7)),
            varintField(2, 2),
            varintField(100, 123),
        )

        assertEquals("known", MigrationParser.parse(migrationUri(root)).entries.single().displayName)
    }

    @Test
    fun `keeps valid entries and reports unsupported or damaged entries`() {
        val hotp = protoMessage(
            bytesField(1, byteArrayOf(1)),
            stringField(2, "hotp"),
            varintField(6, 1),
        )
        val unsupportedAlgorithm = protoMessage(
            bytesField(1, byteArrayOf(2)),
            stringField(2, "sha512"),
            varintField(4, 3),
            varintField(6, 2),
        )
        val unsupportedDigits = protoMessage(
            bytesField(1, byteArrayOf(3)),
            stringField(2, "digits"),
            varintField(5, 3),
            varintField(6, 2),
        )
        val root = protoMessage(
            bytesField(1, basicEntry("valid")),
            bytesField(1, hotp),
            bytesField(1, unsupportedAlgorithm),
            bytesField(1, unsupportedDigits),
            bytesField(1, byteArrayOf(0x0a, 0x05, 0x01)),
            varintField(2, 2),
        )

        val batch = MigrationParser.parse(migrationUri(root))

        assertEquals(listOf("valid"), batch.entries.map(TotpEntry::displayName))
        assertEquals(
            listOf(
                MigrationEntryIssue.HOTP,
                MigrationEntryIssue.UNSUPPORTED_ALGORITHM,
                MigrationEntryIssue.UNSUPPORTED_DIGITS,
                MigrationEntryIssue.MALFORMED,
            ),
            batch.issues,
        )
    }

    @Test
    fun `returns report when migration contains no supported entries`() {
        val hotp = protoMessage(
            bytesField(1, byteArrayOf(1)),
            stringField(2, "hotp"),
            varintField(6, 1),
        )

        val batch = MigrationParser.parse(migrationUri(payload(hotp)))

        assertEquals(emptyList<TotpEntry>(), batch.entries)
        assertEquals(listOf(MigrationEntryIssue.HOTP), batch.issues)
    }

    @Test
    fun `rejects unsupported versions and invalid batches`() {
        listOf(
            migrationUri(protoMessage(bytesField(1, basicEntry("missing version")))),
            migrationUri(payload(basicEntry("zero"), version = 0)),
            migrationUri(payload(basicEntry("future"), version = 3)),
            migrationUri(payload(basicEntry("bad batch"), batchSize = 2, batchIndex = 2)),
        ).forEach { uri ->
            assertThrows(uri, IllegalArgumentException::class.java) { MigrationParser.parse(uri) }
        }
    }

    @Test
    fun `rejects malformed Base64 and truncated protobuf`() {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationParser.parse("otpauth-migration://offline?data=not_base64!")
        }
        val truncatedLengthDelimited = byteArrayOf(0x0a, 0x05, 0x01)
        assertThrows(IllegalArgumentException::class.java) {
            MigrationParser.parse(migrationUri(truncatedLengthDelimited))
        }
        val overflowingVarint = protoMessage(
            bytesField(1, basicEntry("valid")),
            byteArrayOf(0x10),
            ByteArray(9) { 0x80.toByte() },
            byteArrayOf(0x02),
        )
        assertThrows(IllegalArgumentException::class.java) {
            MigrationParser.parse(migrationUri(overflowingVarint))
        }
    }

    private fun basicEntry(name: String) = protoMessage(
        bytesField(1, byteArrayOf(1, 2, 3, 4)),
        stringField(2, name),
        varintField(4, 1),
        varintField(5, 1),
        varintField(6, 2),
    )

    private fun payload(
        entry: ByteArray,
        version: Int = 2,
        batchSize: Int = 1,
        batchIndex: Int = 0,
        batchId: Int = 0,
    ) = protoMessage(
        bytesField(1, entry),
        varintField(2, version),
        varintField(3, batchSize),
        varintField(4, batchIndex),
        varintField(5, batchId),
    )

    private fun migrationUri(payload: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(payload)
        return "otpauth-migration://offline?data=" +
            URLEncoder.encode(encoded, StandardCharsets.UTF_8.name())
    }

    private fun stringField(number: Int, value: String) = bytesField(number, value.toByteArray())

    private fun bytesField(number: Int, value: ByteArray) = protoMessage(
        varint((number shl 3) or 2),
        varint(value.size),
        value,
    )

    private fun varintField(number: Int, value: Int) = protoMessage(
        varint(number shl 3),
        varint(value),
    )

    private fun varint(value: Int): ByteArray {
        var remaining = value
        val result = mutableListOf<Byte>()
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            result += next.toByte()
        } while (remaining != 0)
        return result.toByteArray()
    }

    private fun protoMessage(vararg parts: ByteArray): ByteArray {
        val result = ByteArray(parts.sumOf(ByteArray::size))
        var offset = 0
        parts.forEach { part ->
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }
}
