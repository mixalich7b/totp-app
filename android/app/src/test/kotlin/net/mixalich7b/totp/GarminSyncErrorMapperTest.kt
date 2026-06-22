package net.mixalich7b.totp

import com.garmin.android.connectiq.ConnectIQ
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GarminSyncErrorMapperTest {
    @Test
    fun `every Garmin message status has an explicit user message`() {
        val mappings = ConnectIQ.IQMessageStatus.entries.associateWith(::messageStatusMessageRes)

        assertEquals(ConnectIQ.IQMessageStatus.entries.size, mappings.size)
        mappings.values.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun `every SDK initialization status has an explicit user message`() {
        val mappings = ConnectIQ.IQSdkErrorStatus.entries.associateWith(::sdkErrorMessageRes)

        assertEquals(ConnectIQ.IQSdkErrorStatus.entries.size, mappings.size)
        mappings.values.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun `watch errors map to safe categories without reflecting their text`() {
        assertEquals(R.string.sync_watch_incompatible, watchErrorMessageRes("Unsupported protocol"))
        assertEquals(R.string.sync_watch_incompatible, watchErrorMessageRes("Invalid clear"))
        assertEquals(R.string.sync_watch_newer_revision, watchErrorMessageRes("Stale revision"))
        assertEquals(R.string.sync_watch_incomplete, watchErrorMessageRes("Wrong sequence"))
        assertEquals(R.string.sync_watch_unsupported_entry, watchErrorMessageRes("Unsupported TOTP"))
        assertEquals(R.string.sync_watch_checksum, watchErrorMessageRes("Checksum mismatch"))
        assertEquals(R.string.sync_watch_rejected, watchErrorMessageRes("secret-like unknown text"))
        assertEquals(R.string.sync_watch_rejected, watchErrorMessageRes(null))
        assertEquals("checksum", watchErrorLogCategory("Checksum mismatch"))
        assertEquals("unknown", watchErrorLogCategory("secret-like unknown text"))
    }

    @Test
    fun `response must contain the exact active transfer id`() {
        assertEquals(true, responseMatchesTransfer("tx-active", "tx-active"))
        assertEquals(false, responseMatchesTransfer("tx-old", "tx-active"))
        assertEquals(false, responseMatchesTransfer(null, "tx-active"))
        assertEquals(false, responseMatchesTransfer("tx-active", null))
    }

    @Test
    fun `snapshot ACK requires matching transfer revision and operation`() {
        assertEquals(
            WatchResponse.SnapshotAck(7),
            classifyWatchResponse(
                mapOf("t" to "a", "x" to "tx", "r" to 7),
                "tx",
                7,
                PendingOperation.SNAPSHOT,
            ),
        )
        listOf(
            mapOf("t" to "a", "x" to "old", "r" to 7),
            mapOf("t" to "a", "x" to "tx", "r" to 8),
            mapOf("t" to "a", "x" to "tx"),
        ).forEach { message ->
            assertEquals(
                WatchResponse.Ignore,
                classifyWatchResponse(message, "tx", 7, PendingOperation.SNAPSHOT),
            )
        }
        assertEquals(
            WatchResponse.Ignore,
            classifyWatchResponse(
                mapOf("t" to "a", "x" to "tx", "r" to 7),
                "tx",
                7,
                PendingOperation.CLEAR_WATCH,
            ),
        )
    }

    @Test
    fun `clear ACK and errors require active transfer`() {
        assertEquals(
            WatchResponse.ClearAck,
            classifyWatchResponse(mapOf("t" to ":z", "x" to "tx"), "tx", 0, PendingOperation.CLEAR_WATCH),
        )
        assertEquals(
            WatchResponse.Error("Checksum mismatch"),
            classifyWatchResponse(
                mapOf("t" to "e", "x" to "tx", "m" to "Checksum mismatch"),
                "tx",
                1,
                PendingOperation.SNAPSHOT,
            ),
        )
        assertEquals(
            WatchResponse.Ignore,
            classifyWatchResponse(mapOf("t" to "e", "m" to "ignored"), "tx", 1, PendingOperation.SNAPSHOT),
        )
    }
}
