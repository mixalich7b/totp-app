package net.mixalich7b.totp

internal class TotpCodeCache(
    private val generator: (TotpEntry, Long) -> String = Totp::generate,
) {
    private data class CachedCode(
        val entry: TotpEntry,
        val timeStep: Long,
        val code: String,
    )

    private val codesById = mutableMapOf<String, CachedCode>()

    fun codeFor(entry: TotpEntry, unixSeconds: Long): String {
        val timeStep = unixSeconds / entry.periodSeconds
        val cached = codesById[entry.id]
        if (cached != null && cached.entry === entry && cached.timeStep == timeStep) {
            return cached.code
        }

        return generator(entry, unixSeconds).also { code ->
            codesById[entry.id] = CachedCode(entry, timeStep, code)
        }
    }

    fun retain(entries: List<TotpEntry>) {
        val currentEntriesById = entries.associateBy(TotpEntry::id)
        codesById.entries.removeAll { (id, cached) ->
            currentEntriesById[id] !== cached.entry
        }
    }

    fun clear() {
        codesById.clear()
    }
}
