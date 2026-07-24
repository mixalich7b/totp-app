package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TotpCodeCacheTest {
    @Test
    fun `reuses code within time step and regenerates at boundary`() {
        val generator = CountingGenerator()
        val cache = TotpCodeCache(generator::generate)
        val entry = entry("first", periodSeconds = 30)

        val initial = cache.codeFor(entry, 30)
        val beforeBoundary = cache.codeFor(entry, 59)
        val afterBoundary = cache.codeFor(entry, 60)

        assertEquals(initial, beforeBoundary)
        assertNotEquals(initial, afterBoundary)
        assertEquals(2, generator.calls)
    }

    @Test
    fun `tracks time steps independently for different periods`() {
        val generator = CountingGenerator()
        val cache = TotpCodeCache(generator::generate)
        val shortPeriod = entry("short", periodSeconds = 30)
        val longPeriod = entry("long", periodSeconds = 60)

        val shortInitial = cache.codeFor(shortPeriod, 29)
        val longInitial = cache.codeFor(longPeriod, 29)
        val shortNext = cache.codeFor(shortPeriod, 30)
        val longSame = cache.codeFor(longPeriod, 30)

        assertNotEquals(shortInitial, shortNext)
        assertEquals(longInitial, longSame)
        assertEquals(3, generator.calls)
    }

    @Test
    fun `replacement with same id invalidates cached code`() {
        val generator = CountingGenerator()
        val cache = TotpCodeCache(generator::generate)
        val original = entry("same", periodSeconds = 30)
        val replacement = original.copy(
            displayName = "replacement",
            secret = byteArrayOf(9, 8, 7),
        )

        val originalCode = cache.codeFor(original, 42)
        val replacementCode = cache.codeFor(replacement, 42)

        assertNotEquals(originalCode, replacementCode)
        assertEquals(2, generator.calls)
    }

    @Test
    fun `retain preserves unchanged entries and removes deleted entries`() {
        val generator = CountingGenerator()
        val cache = TotpCodeCache(generator::generate)
        val first = entry("first", periodSeconds = 30)
        val second = entry("second", periodSeconds = 30)

        val firstCode = cache.codeFor(first, 42)
        cache.retain(listOf(first, second))
        assertEquals(firstCode, cache.codeFor(first, 42))
        assertEquals(1, generator.calls)

        cache.retain(listOf(second))
        assertNotEquals(firstCode, cache.codeFor(first, 42))
        assertEquals(2, generator.calls)
    }

    @Test
    fun `clear invalidates all cached codes`() {
        val generator = CountingGenerator()
        val cache = TotpCodeCache(generator::generate)
        val entry = entry("first", periodSeconds = 30)

        val initial = cache.codeFor(entry, 42)
        cache.clear()
        val afterClear = cache.codeFor(entry, 42)

        assertNotEquals(initial, afterClear)
        assertEquals(2, generator.calls)
    }

    @Test
    fun `clock changes regenerate only when computed time step changes`() {
        val generator = CountingGenerator()
        val cache = TotpCodeCache(generator::generate)
        val entry = entry("first", periodSeconds = 30)

        val initial = cache.codeFor(entry, 89)
        val sameStepAfterSmallCorrection = cache.codeFor(entry, 60)
        val afterBackwardJump = cache.codeFor(entry, 59)
        val afterForwardJump = cache.codeFor(entry, 301)

        assertEquals(initial, sameStepAfterSmallCorrection)
        assertNotEquals(initial, afterBackwardJump)
        assertNotEquals(afterBackwardJump, afterForwardJump)
        assertEquals(3, generator.calls)
    }

    private class CountingGenerator {
        var calls = 0
            private set

        fun generate(entry: TotpEntry, unixSeconds: Long): String {
            calls += 1
            return "${entry.id}:$unixSeconds:$calls"
        }
    }

    private fun entry(id: String, periodSeconds: Int) = TotpEntry(
        id = id,
        displayName = id,
        issuer = "",
        accountName = "",
        secret = byteArrayOf(1, 2, 3),
        periodSeconds = periodSeconds,
    )
}
