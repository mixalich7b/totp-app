package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationBatchCollectorTest {
    @Test
    fun `assembles multipart migration in index order`() {
        val collector = MigrationBatchCollector()

        assertEquals(
            MigrationBatchResult.Pending(1, 3),
            collector.add(batch(index = 2, name = "third")),
        )
        assertEquals(
            MigrationBatchResult.Pending(2, 3),
            collector.add(batch(index = 0, name = "first")),
        )
        val result = collector.add(batch(index = 1, name = "second")) as MigrationBatchResult.Complete

        assertEquals(listOf("first", "second", "third"), result.entries.map(TotpEntry::displayName))
        assertEquals(emptyList<MigrationEntryIssue>(), result.issues)
    }

    @Test
    fun `changing batch wipes abandoned secrets`() {
        val collector = MigrationBatchCollector()
        val abandoned = batch(index = 0, name = "old", id = 10)
        collector.add(abandoned)

        assertEquals(
            MigrationBatchResult.Pending(1, 3),
            collector.add(batch(index = 0, name = "new", id = 11)),
        )
        assertTrue(abandoned.entries.single().secret.all { it == 0.toByte() })
    }

    @Test
    fun `replacing duplicate part wipes previous secret`() {
        val collector = MigrationBatchCollector()
        val original = batch(index = 0, name = "old")
        collector.add(original)
        collector.add(batch(index = 0, name = "new"))

        assertTrue(original.entries.single().secret.all { it == 0.toByte() })
    }

    @Test
    fun `clear wipes pending secrets`() {
        val collector = MigrationBatchCollector()
        val pending = batch(index = 1, name = "pending")
        collector.add(pending)

        collector.clear()

        assertTrue(pending.entries.single().secret.all { it == 0.toByte() })
    }

    @Test
    fun `assembles issues from every batch part`() {
        val collector = MigrationBatchCollector()
        val first = batch(index = 0, name = "first").copy(issues = listOf(MigrationEntryIssue.HOTP))
        val second = batch(index = 1, name = "second").copy(
            issues = listOf(MigrationEntryIssue.MALFORMED, MigrationEntryIssue.UNSUPPORTED_ALGORITHM),
        )
        val third = batch(index = 2, name = "third")

        collector.add(second)
        collector.add(third)
        val result = collector.add(first) as MigrationBatchResult.Complete

        assertEquals(
            listOf(
                MigrationEntryIssue.HOTP,
                MigrationEntryIssue.MALFORMED,
                MigrationEntryIssue.UNSUPPORTED_ALGORITHM,
            ),
            result.issues,
        )
    }

    private fun batch(index: Int, name: String, id: Long = 10) = MigrationBatch(
        entries = listOf(
            TotpEntry(
                displayName = name,
                issuer = "",
                accountName = name,
                secret = byteArrayOf(1, 2, 3),
            )
        ),
        issues = emptyList(),
        batchSize = 3,
        batchIndex = index,
        batchId = id,
    )
}
