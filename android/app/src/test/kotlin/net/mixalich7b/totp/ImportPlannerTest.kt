package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ImportPlannerTest {
    @Test
    fun `duplicate matching normalizes issuer and account but binds TOTP parameters`() {
        val existing = entry("stored", "Example", "Alice", byteArrayOf(1, 2, 3))
        val duplicate = entry("imported", " example ", "ALICE", byteArrayOf(1, 2, 3))
        val differentDigits = duplicate.copy(id = "different", digits = 8)

        assertEquals(1, ImportPlanner.duplicateCount(listOf(existing), listOf(duplicate)))
        assertEquals(0, ImportPlanner.duplicateCount(listOf(existing), listOf(differentDigits)))
    }

    @Test
    fun `skip keeps new entries and omits duplicates`() {
        val existing = entry("stored", "Example", "Alice", byteArrayOf(1))
        val duplicate = entry("duplicate", "Example", "Alice", byteArrayOf(1))
        val fresh = entry("fresh", "Example", "Bob", byteArrayOf(2))

        val plan = ImportPlanner.plan(listOf(existing), listOf(duplicate, fresh), ImportDuplicatePolicy.SKIP)

        assertEquals(1, plan.duplicateCount)
        assertEquals(listOf("fresh"), plan.entriesToWrite.map(TotpEntry::id))
        assertSame(fresh, plan.entriesToWrite.single())
    }

    @Test
    fun `replace preserves stored id and creation time`() {
        val existing = entry("stored", "Example", "Alice", byteArrayOf(1), name = "Old", createdAt = 10)
        val imported = entry("imported", "Example", "Alice", byteArrayOf(1), name = "New", createdAt = 20)

        val replacement = ImportPlanner.plan(
            listOf(existing),
            listOf(imported),
            ImportDuplicatePolicy.REPLACE,
        ).entriesToWrite.single()

        assertEquals("stored", replacement.id)
        assertEquals("New", replacement.displayName)
        assertEquals(10L, replacement.createdAt)
    }

    @Test
    fun `keep both writes duplicate with its new id`() {
        val existing = entry("stored", "Example", "Alice", byteArrayOf(1))
        val imported = entry("imported", "Example", "Alice", byteArrayOf(1))

        val plan = ImportPlanner.plan(listOf(existing), listOf(imported), ImportDuplicatePolicy.KEEP_BOTH)

        assertEquals(1, plan.duplicateCount)
        assertEquals(listOf("imported"), plan.entriesToWrite.map(TotpEntry::id))
    }

    @Test
    fun `duplicates inside one batch follow selected policy`() {
        val first = entry("first", "Example", "Alice", byteArrayOf(1), name = "First")
        val second = entry("second", "Example", "Alice", byteArrayOf(1), name = "Second")

        val skipped = ImportPlanner.plan(emptyList(), listOf(first, second), ImportDuplicatePolicy.SKIP)
        val replaced = ImportPlanner.plan(emptyList(), listOf(first, second), ImportDuplicatePolicy.REPLACE)
        val kept = ImportPlanner.plan(emptyList(), listOf(first, second), ImportDuplicatePolicy.KEEP_BOTH)

        assertEquals(listOf("first"), skipped.entriesToWrite.map(TotpEntry::id))
        assertEquals(listOf("first"), replaced.entriesToWrite.map(TotpEntry::id))
        assertEquals("Second", replaced.entriesToWrite.single().displayName)
        assertEquals(listOf("first", "second"), kept.entriesToWrite.map(TotpEntry::id))
    }

    private fun entry(
        id: String,
        issuer: String,
        account: String,
        secret: ByteArray,
        name: String = id,
        createdAt: Long = 1,
    ) = TotpEntry(
        id = id,
        displayName = name,
        issuer = issuer,
        accountName = account,
        secret = secret,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
