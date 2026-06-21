package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncProtocolTest {
    @Test
    fun `snapshot hash matches Garmin canonical vector`() {
        val entry = entry()

        assertEquals(
            "0e5a08fb440ef9e732a896ea987e594250137596428a44fdd4d9b15b58bb327c",
            SnapshotHasher.sha256(listOf(entry)),
        )
    }

    @Test
    fun `snapshot hash binds order metadata and secret`() {
        val first = entry()
        val second = entry().copy(id = "00000000-0000-0000-0000-000000000002", displayName = "Другой")
        val baseline = SnapshotHasher.sha256(listOf(first, second))

        assertNotEquals(baseline, SnapshotHasher.sha256(listOf(second, first)))
        assertNotEquals(baseline, SnapshotHasher.sha256(listOf(first.copy(digits = 8), second)))
        assertNotEquals(
            baseline,
            SnapshotHasher.sha256(listOf(first.copy(secret = first.secret.copyOf().apply { this[0] = 10 }), second)),
        )
    }

    @Test
    fun `empty snapshot has standard SHA256 hash`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SnapshotHasher.sha256(emptyList()),
        )
    }

    private fun entry() = TotpEntry(
        id = "00000000-0000-0000-0000-000000000001",
        displayName = "Test",
        issuer = "",
        accountName = "",
        secret = byteArrayOf(15, -40, 65, 12, 32, -9, 37, -19, 25, -36),
        algorithm = TotpAlgorithm.SHA1,
        digits = 6,
        periodSeconds = 30,
        createdAt = 0,
        updatedAt = 0,
    )
}
