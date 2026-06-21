package net.mixalich7b.totp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class AesGcmEnvelopeCryptoTest {
    private val crypto = AesGcmEnvelopeCrypto(
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    )
    private val aad = "entry-id|1|42".toByteArray()
    private val plaintext = "encrypted entry payload".toByteArray()

    @Test
    fun `round trip preserves plaintext`() {
        val envelope = crypto.encrypt(aad, plaintext)

        assertArrayEquals(plaintext, crypto.decrypt(aad, envelope.iv, envelope.ciphertext))
    }

    @Test
    fun `each encryption uses a unique provider generated IV`() {
        val first = crypto.encrypt(aad, plaintext)
        val second = crypto.encrypt(aad, plaintext)

        assertFalse(first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
    }

    @Test
    fun `different AAD cannot decrypt ciphertext`() {
        val envelope = crypto.encrypt(aad, plaintext)

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt("entry-id|1|43".toByteArray(), envelope.iv, envelope.ciphertext)
        }
    }

    @Test
    fun `tampered ciphertext or GCM tag is rejected`() {
        val envelope = crypto.encrypt(aad, plaintext)
        val tampered = envelope.ciphertext.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(aad, envelope.iv, tampered)
        }
    }
}
