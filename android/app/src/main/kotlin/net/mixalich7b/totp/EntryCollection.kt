package net.mixalich7b.totp

import java.util.Collections

internal class EntryCollection {
    private val mutableEntries = mutableListOf<TotpEntry>()

    val entries: List<TotpEntry> = Collections.unmodifiableList(mutableEntries)

    val size: Int
        get() = mutableEntries.size

    fun replaceAll(loadedEntries: List<TotpEntry>) {
        clear()
        mutableEntries.addAll(loadedEntries)
    }

    fun addCopy(entry: TotpEntry, updatedAt: Long): TotpEntry {
        val saved = repositoryOwnedCopy(entry, updatedAt)
        mutableEntries.add(saved)
        return saved
    }

    fun upsertCopies(importedEntries: List<TotpEntry>, updatedAt: Long) {
        importedEntries.forEach { incoming ->
            val saved = repositoryOwnedCopy(incoming, updatedAt)
            val index = mutableEntries.indexOfFirst { it.id == saved.id }
            if (index >= 0) {
                mutableEntries[index].secret.fill(0)
                mutableEntries[index] = saved
            } else {
                mutableEntries.add(saved)
            }
        }
    }

    fun remove(id: String): Boolean {
        val index = mutableEntries.indexOfFirst { it.id == id }
        if (index < 0) return false
        mutableEntries.removeAt(index).secret.fill(0)
        return true
    }

    fun clear() {
        mutableEntries.forEach { it.secret.fill(0) }
        mutableEntries.clear()
    }
}
