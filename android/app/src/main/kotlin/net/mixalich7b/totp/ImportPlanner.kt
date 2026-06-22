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
        val known = existing.mapTo(mutableSetOf()) { it.duplicateKey() }
        var count = 0
        imported.forEach { entry ->
            val key = entry.duplicateKey()
            if (key in known) count++
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
        val firstIndexByKey = mutableMapOf<DuplicateKey, Int>()
        working.forEachIndexed { index, entry -> firstIndexByKey.putIfAbsent(entry.duplicateKey(), index) }
        val writes = linkedMapOf<String, TotpEntry>()
        var duplicates = 0

        imported.forEach { entry ->
            val key = entry.duplicateKey()
            val duplicateIndex = firstIndexByKey[key]
            if (duplicateIndex == null) {
                firstIndexByKey[key] = working.size
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
        SecretBytes(secret),
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
        val secret: SecretBytes,
        val algorithm: TotpAlgorithm,
        val digits: Int,
        val periodSeconds: Int,
    )

    private class SecretBytes(private val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is SecretBytes && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }
}
