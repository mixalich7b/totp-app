package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryCollectionTest {
    @Test
    fun `add stores an independent secret copy`() {
        val collection = EntryCollection()
        val source = entry("first", byteArrayOf(1, 2, 3))

        val stored = collection.addCopy(source, updatedAt = 42)
        stored.secret.fill(0)

        assertFalse(source.secret === stored.secret)
        assertArrayEquals(byteArrayOf(1, 2, 3), source.secret)
        assertEquals(42, stored.updatedAt)
    }

    @Test
    fun `upsert replaces by id and clears previous secret`() {
        val previous = entry("same", byteArrayOf(1, 2, 3))
        val replacement = entry("same", byteArrayOf(4, 5, 6))
        val collection = EntryCollection()
        collection.replaceAll(listOf(previous))

        collection.upsertCopies(listOf(replacement), updatedAt = 50)

        assertEquals(1, collection.size)
        assertArrayEquals(byteArrayOf(0, 0, 0), previous.secret)
        assertArrayEquals(byteArrayOf(4, 5, 6), collection.entries.single().secret)
        assertFalse(replacement.secret === collection.entries.single().secret)
    }

    @Test
    fun `update replaces in place and keeps an independent secret copy`() {
        val previous = entry("same", byteArrayOf(1, 2, 3))
        val remaining = entry("remaining", byteArrayOf(4))
        val collection = EntryCollection()
        collection.replaceAll(listOf(previous, remaining))
        val edited = previous.copy(displayName = "Edited")

        val stored = collection.updateCopy(edited, updatedAt = 50)

        assertEquals(listOf("same", "remaining"), collection.entries.map(TotpEntry::id))
        assertEquals("Edited", stored.displayName)
        assertEquals(50, stored.updatedAt)
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.secret)
        assertArrayEquals(byteArrayOf(0, 0, 0), previous.secret)
        assertFalse(edited.secret === stored.secret)
    }

    @Test
    fun `remove clears removed secret`() {
        val removed = entry("removed", byteArrayOf(7, 8))
        val remaining = entry("remaining", byteArrayOf(9))
        val collection = EntryCollection()
        collection.replaceAll(listOf(removed, remaining))

        assertTrue(collection.remove("removed"))

        assertArrayEquals(byteArrayOf(0, 0), removed.secret)
        assertEquals(listOf("remaining"), collection.entries.map { it.id })
    }

    @Test
    fun `clear zeros every owned secret`() {
        val first = entry("first", byteArrayOf(1))
        val second = entry("second", byteArrayOf(2))
        val collection = EntryCollection()
        collection.replaceAll(listOf(first, second))

        collection.clear()

        assertEquals(0, collection.size)
        assertArrayEquals(byteArrayOf(0), first.secret)
        assertArrayEquals(byteArrayOf(0), second.secret)
    }

    @Test
    fun `replace all clears previous entries and keeps a live read only view`() {
        val previous = entry("previous", byteArrayOf(1, 2))
        val loaded = entry("loaded", byteArrayOf(3, 4))
        val collection = EntryCollection()
        val view = collection.entries
        collection.replaceAll(listOf(previous))

        collection.replaceAll(listOf(loaded))

        assertArrayEquals(byteArrayOf(0, 0), previous.secret)
        assertEquals(listOf("loaded"), view.map { it.id })
        assertThrows(UnsupportedOperationException::class.java) {
            (view as MutableList).clear()
        }
    }

    private fun entry(id: String, secret: ByteArray) = TotpEntry(
        id = id,
        displayName = id,
        issuer = "",
        accountName = "",
        secret = secret,
    )
}
