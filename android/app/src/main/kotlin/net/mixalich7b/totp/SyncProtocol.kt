package net.mixalich7b.totp

import java.security.MessageDigest

internal object SnapshotHasher {
    fun sha256(entries: List<TotpEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            listOf(
                entry.id,
                entry.displayName,
                entry.algorithm.wireValue.toString(),
                entry.digits.toString(),
                entry.periodSeconds.toString(),
            ).forEach { value ->
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            digest.update(entry.secret)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
