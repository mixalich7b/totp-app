package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class CopyConfirmationAppearanceTest {
    @Test
    fun `copy confirmation stays dark and readable on app surface`() {
        val appearance = copyConfirmationAppearance()

        assertEquals(0xff, appearance.backgroundArgb ushr 24)
        assertEquals(0xff, appearance.textArgb ushr 24)
        assertTrue(relativeLuminance(appearance.backgroundArgb) < relativeLuminance(APP_SURFACE_ARGB))
        assertTrue(contrastRatio(appearance.backgroundArgb, appearance.textArgb) >= MINIMUM_CONTRAST)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val lighter = max(relativeLuminance(first), relativeLuminance(second))
        val darker = min(relativeLuminance(first), relativeLuminance(second))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val srgb = ((argb ushr shift) and 0xff) / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val APP_SURFACE_ARGB = -328966 // #FFFAFAFA
        const val MINIMUM_CONTRAST = 7.0
    }
}
