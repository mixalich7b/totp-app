package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecureEntryOwnershipTest {
    @Test
    fun `repository copy owns an independent secret array`() {
        val callerSecret = byteArrayOf(1, 2, 3, 4)
        val entry = TotpEntry("id", "Test", "", "", callerSecret)

        val owned = repositoryOwnedCopy(entry, updatedAt = 42)
        owned.secret.fill(0)

        assertFalse(callerSecret === owned.secret)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), callerSecret)
    }
}
