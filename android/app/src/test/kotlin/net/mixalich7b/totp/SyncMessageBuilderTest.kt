package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncMessageBuilderTest {
    @Test
    fun `builds begin ordered chunks and commit`() {
        val entries = listOf(
            TotpEntry("first", "First", "", "", byteArrayOf(0, -1)),
            TotpEntry("second", "Second", "", "", byteArrayOf(2)),
        )

        val messages = buildSyncMessages(entries, revision = 7, transferId = "tx")

        assertEquals(listOf("b", "c", "c", "m"), messages.map { it["t"] })
        assertEquals(7L, messages.first()["r"])
        assertEquals(2, messages.first()["n"])
        assertEquals(listOf(0, 255), ((messages[1]["e"] as Map<*, *>)["s"]))
        assertEquals(1, messages[2]["q"])
        assertEquals("tx", messages.last()["x"])
    }

    @Test
    fun `rejects snapshot beyond watch limit`() {
        val entry = TotpEntry("id", "Test", "", "", byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            buildSyncMessages(List(GarminSyncManager.MAX_SYNC_ENTRIES + 1) { entry }, 1, "tx")
        }
    }
}
