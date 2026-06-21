package net.mixalich7b.totp

import java.text.Normalizer
import java.util.Locale

enum class ImportDuplicatePolicy {
    SKIP,
    REPLACE,
    KEEP_BOTH,
}

data class ImportPlan(
    val entriesToWrite: List<TotpEntry>,
    val duplicateCount: Int,
)

object ImportPlanner {
    fun duplicateCount(existing: List<TotpEntry>, imported: List<TotpEntry>): Int {
        val known = existing.mapTo(mutableListOf()) { it.duplicateKey() }
        var count = 0
        imported.forEach { entry ->
            val key = entry.duplicateKey()
            if (known.contains(key)) count++
            known += key
        }
        return count
    }

    fun plan(
        existing: List<TotpEntry>,
        imported: List<TotpEntry>,
        duplicatePolicy: ImportDuplicatePolicy,
    ): ImportPlan {
        val working = existing.toMutableList()
        val writes = linkedMapOf<String, TotpEntry>()
        var duplicates = 0

        imported.forEach { entry ->
            val duplicateIndex = working.indexOfFirst { it.duplicateKey() == entry.duplicateKey() }
            if (duplicateIndex < 0) {
                working += entry
                writes[entry.id] = entry
                return@forEach
            }

            duplicates++
            when (duplicatePolicy) {
                ImportDuplicatePolicy.SKIP -> Unit
                ImportDuplicatePolicy.KEEP_BOTH -> {
                    working += entry
                    writes[entry.id] = entry
                }
                ImportDuplicatePolicy.REPLACE -> {
                    val replaced = working[duplicateIndex]
                    val replacement = entry.copy(id = replaced.id, createdAt = replaced.createdAt)
                    working[duplicateIndex] = replacement
                    writes[replacement.id] = replacement
                }
            }
        }

        return ImportPlan(writes.values.toList(), duplicates)
    }

    private fun TotpEntry.duplicateKey() = DuplicateKey(
        normalizedText(issuer),
        normalizedText(accountName),
        secret.toList(),
        algorithm,
        digits,
        periodSeconds,
    )

    private fun normalizedText(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    private data class DuplicateKey(
        val issuer: String,
        val accountName: String,
        val secret: List<Byte>,
        val algorithm: TotpAlgorithm,
        val digits: Int,
        val periodSeconds: Int,
    )
}
