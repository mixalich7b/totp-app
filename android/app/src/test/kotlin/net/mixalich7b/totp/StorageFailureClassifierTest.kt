package net.mixalich7b.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.InvalidKeyException
import javax.crypto.AEADBadTagException

class StorageFailureClassifierTest {
    @Test
    fun `locked device takes precedence over invalid key`() {
        val kind = classifyStorageCryptoFailure(InvalidKeyException("locked"), deviceLocked = true)

        assertEquals(StorageFailureKind.DEVICE_LOCKED, kind)
        assertFalse(kind.canRecoverWithLocalReset)
    }

    @Test
    fun `gcm authentication failure is resettable while unlocked`() {
        val kind = classifyStorageCryptoFailure(AEADBadTagException("bad tag"), deviceLocked = false)

        assertEquals(StorageFailureKind.KEY_MISMATCH_OR_CORRUPT, kind)
        assertTrue(kind.canRecoverWithLocalReset)
    }

    @Test
    fun `generic io failure is not resettable`() {
        val kind = classifyStorageCryptoFailure(IOException("read failed"), deviceLocked = false)

        assertEquals(StorageFailureKind.IO_OR_RUNTIME, kind)
        assertFalse(kind.canRecoverWithLocalReset)
    }

    @Test
    fun `nested crypto failure is classified from cause chain`() {
        val kind = classifyStorageCryptoFailure(RuntimeException(AEADBadTagException()), deviceLocked = false)

        assertEquals(StorageFailureKind.KEY_MISMATCH_OR_CORRUPT, kind)
    }
}
