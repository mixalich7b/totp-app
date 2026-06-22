package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class SyncProtocolTest {
    @Test
    fun `snapshot hash matches Garmin canonical vector`() {
        val entry = entry()

        assertEquals(
            "0e5a08fb440ef9e732a896ea987e594250137596428a44fdd4d9b15b58bb327c",
            SnapshotHasher.sha256(listOf(entry)),
        )
    }

    @Test
    fun `snapshot hash binds order metadata and secret`() {
        val first = entry()
        val second = entry().copy(id = "00000000-0000-0000-0000-000000000002", displayName = "Другой")
        val baseline = SnapshotHasher.sha256(listOf(first, second))

        assertNotEquals(baseline, SnapshotHasher.sha256(listOf(second, first)))
        assertNotEquals(baseline, SnapshotHasher.sha256(listOf(first.copy(digits = 8), second)))
        assertNotEquals(
            baseline,
            SnapshotHasher.sha256(listOf(first.copy(secret = first.secret.copyOf().apply { this[0] = 10 }), second)),
        )
    }

    @Test
    fun `empty snapshot has standard SHA256 hash`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SnapshotHasher.sha256(emptyList()),
        )
    }

    @Test
    fun `snapshot hash properties bind every transmitted field`() {
        val random = Random(0x5A4A5)
        repeat(250) { iteration ->
            val entries = List(random.nextInt(1, 9)) { index -> randomEntry(random, iteration, index) }
            val baseline = SnapshotHasher.sha256(entries)
            val copied = entries.map { it.copy(secret = it.secret.copyOf()) }
            assertEquals("iteration=$iteration", baseline, SnapshotHasher.sha256(copied))

            val target = random.nextInt(entries.size)
            fun mutated(replacement: TotpEntry): List<TotpEntry> = entries.toMutableList().apply {
                this[target] = replacement
            }
            val original = entries[target]
            assertNotEquals(baseline, SnapshotHasher.sha256(mutated(original.copy(id = original.id + "x"))))
            assertNotEquals(baseline, SnapshotHasher.sha256(mutated(original.copy(displayName = original.displayName + "x"))))
            assertNotEquals(
                baseline,
                SnapshotHasher.sha256(mutated(original.copy(
                    algorithm = if (original.algorithm == TotpAlgorithm.SHA1) TotpAlgorithm.SHA256 else TotpAlgorithm.SHA1,
                ))),
            )
            assertNotEquals(baseline, SnapshotHasher.sha256(mutated(original.copy(digits = if (original.digits == 6) 8 else 6))))
            assertNotEquals(
                baseline,
                SnapshotHasher.sha256(mutated(original.copy(periodSeconds = if (original.periodSeconds == 300) 299 else original.periodSeconds + 1))),
            )
            assertNotEquals(
                baseline,
                SnapshotHasher.sha256(mutated(original.copy(secret = original.secret.copyOf().apply {
                    val secretIndex = random.nextInt(size)
                    this[secretIndex] = (this[secretIndex].toInt() xor 1).toByte()
                }))),
            )

            val nonTransmittedChange = original.copy(
                issuer = original.issuer + "x",
                accountName = original.accountName + "x",
                createdAt = original.createdAt + 1,
                updatedAt = original.updatedAt + 1,
                revision = original.revision + 1,
            )
            assertEquals(baseline, SnapshotHasher.sha256(mutated(nonTransmittedChange)))
            if (entries.size > 1) assertNotEquals(baseline, SnapshotHasher.sha256(entries.reversed()))
        }
    }

    private fun entry() = TotpEntry(
        id = "00000000-0000-0000-0000-000000000001",
        displayName = "Test",
        issuer = "",
        accountName = "",
        secret = byteArrayOf(15, -40, 65, 12, 32, -9, 37, -19, 25, -36),
        algorithm = TotpAlgorithm.SHA1,
        digits = 6,
        periodSeconds = 30,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun randomEntry(random: Random, iteration: Int, index: Int) = TotpEntry(
        id = "entry-$iteration-$index",
        displayName = "Код $iteration/$index 😀",
        issuer = "Issuer $index",
        accountName = "account-$iteration@example.com",
        secret = random.nextBytes(random.nextInt(1, 65)),
        algorithm = if (random.nextBoolean()) TotpAlgorithm.SHA1 else TotpAlgorithm.SHA256,
        digits = if (random.nextBoolean()) 6 else 8,
        periodSeconds = random.nextInt(5, 301),
        createdAt = random.nextLong(),
        updatedAt = random.nextLong(),
        revision = random.nextLong(),
    )
}
