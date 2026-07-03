package net.mixalich7b.totp

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import javax.crypto.SecretKey

class SecureEntryRepositoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        clearStorage()
    }

    @After
    fun tearDown() {
        clearStorage()
    }

    @Test
    fun testRoundTripUsesUniqueIvAndKeepsContentOutOfColumns() {
        val first = entry("first-id", "Private name", "Private issuer", byteArrayOf(1, 2, 3, 4))
        val second = entry("second-id", "Second private name", "", byteArrayOf(5, 6, 7, 8))
        SecureEntryRepository(context).use { repository ->
            repository.add(listOf(first, second))
            val restored = repository.list()
            assertEquals(listOf("Private name", "Second private name"), restored.map(TotpEntry::displayName))
        }

        openDatabase(SQLiteDatabase.OPEN_READONLY).use { database ->
            database.query("entries", arrayOf("iv", "ciphertext"), null, null, null, null, "id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val firstIv = cursor.getBlob(0)
                val firstCiphertext = cursor.getBlob(1)
                assertEquals(12, firstIv.size)
                assertFalse(firstCiphertext.contains("Private name".toByteArray()))
                assertFalse(firstCiphertext.contains("Private issuer".toByteArray()))
                assertFalse(firstCiphertext.contains(byteArrayOf(1, 2, 3, 4)))

                assertTrue(cursor.moveToNext())
                val secondIv = cursor.getBlob(0)
                assertEquals(12, secondIv.size)
                assertFalse(firstIv.contentEquals(secondIv))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun testWriteDoesNotMutateCallerOwnedSecret() {
        val secret = byteArrayOf(1, 2, 3, 4)
        val original = secret.copyOf()
        SecureEntryRepository(context).use { repository ->
            repository.add(listOf(entry("caller-owned", "Caller", "", secret)))
        }

        assertTrue(original.contentEquals(secret))
    }

    @Test
    fun testSecretInputDisablesSuggestionsAutofillAndStateSaving() {
        val field = EditText(context).apply { configureSecretInput() }

        assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertTrue(field.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
        assertFalse(field.isSaveEnabled)
    }

    @Test
    fun testMissingKeystoreKeyDoesNotSilentlyReplaceKey() {
        SecureEntryRepository(context).use { repository ->
            repository.add(listOf(entry("lost-key", "Lost key", "", byteArrayOf(9, 8, 7))))
        }
        deleteKey()

        try {
            SecureEntryRepository(context).use(SecureEntryRepository::list)
            fail("StorageUnavailableException expected")
        } catch (error: StorageUnavailableException) {
            assertTrue(error.canRecoverWithLocalReset)
            // Expected: encrypted rows must not cause silent key regeneration.
        }
    }

    @Test
    fun testExplicitResetAfterMissingKeyCreatesFreshUsableStorage() {
        SecureEntryRepository(context).use { repository ->
            repository.add(listOf(entry("lost-key", "Lost key", "", byteArrayOf(9, 8, 7))))
        }
        deleteKey()

        try {
            SecureEntryRepository(context).use(SecureEntryRepository::list)
            fail("StorageUnavailableException expected")
        } catch (error: StorageUnavailableException) {
            assertTrue(error.canRecoverWithLocalReset)
            // The reset must remain explicit after the repository reports the lost key.
        }

        SecureEntryRepository.resetLocalStorage(context)
        SecureEntryRepository(context).use { repository ->
            assertTrue(repository.list().isEmpty())
            assertEquals(1L, repository.add(listOf(entry("fresh", "Fresh", "", byteArrayOf(1, 2, 3)))))
            assertEquals(listOf("Fresh"), repository.list().map(TotpEntry::displayName))
        }
    }

    @Test
    fun testImportReplacesByIdAndAdvancesRevisionOnce() {
        val storedId = "stored-id"
        SecureEntryRepository(context).use { repository ->
            assertEquals(
                1L,
                repository.add(listOf(entry(storedId, "Old name", "Issuer", byteArrayOf(1, 2, 3)))),
            )
            val replacement = entry(storedId, "New name", "Issuer", byteArrayOf(1, 2, 3))
            val fresh = entry("fresh-id", "Fresh", "Issuer", byteArrayOf(4, 5, 6))

            assertEquals(2L, repository.importEntries(listOf(replacement, fresh)))

            val restored = repository.list()
            assertEquals(listOf(storedId, "fresh-id"), restored.map(TotpEntry::id))
            assertEquals(listOf("New name", "Fresh"), restored.map(TotpEntry::displayName))
            assertEquals(2L, repository.revision())
        }
    }

    @Test
    fun testUpdatePreservesIdentitySecretCreationTimeAndListPosition() {
        val secret = byteArrayOf(1, 2, 3)
        SecureEntryRepository(context).use { repository ->
            repository.add(
                listOf(
                    entry("edited-id", "Old name", "Old issuer", secret),
                    entry("remaining-id", "Remaining", "", byteArrayOf(4)),
                ),
            )
            val stored = repository.list().first()
            val edited = stored.copy(
                displayName = "New name",
                issuer = "New issuer",
                accountName = "new account",
                algorithm = TotpAlgorithm.SHA256,
                digits = 8,
                periodSeconds = 45,
            )

            assertEquals(2L, repository.update(edited))

            val restored = repository.list()
            assertEquals(listOf("edited-id", "remaining-id"), restored.map(TotpEntry::id))
            assertEquals("New name", restored.first().displayName)
            assertEquals("New issuer", restored.first().issuer)
            assertEquals("new account", restored.first().accountName)
            assertEquals(TotpAlgorithm.SHA256, restored.first().algorithm)
            assertEquals(8, restored.first().digits)
            assertEquals(45, restored.first().periodSeconds)
            assertEquals(stored.createdAt, restored.first().createdAt)
            assertTrue(stored.secret.contentEquals(restored.first().secret))
            assertTrue(secret.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals(2L, repository.revision())
        }
    }

    @Test
    fun testCorruptedGcmTagIsRejected() {
        SecureEntryRepository(context).use { repository ->
            repository.add(listOf(entry("corrupt", "Corrupt", "", byteArrayOf(4, 3, 2, 1))))
        }
        openDatabase(SQLiteDatabase.OPEN_READWRITE).use { database ->
            val ciphertext = database.query(
                "entries",
                arrayOf("ciphertext"),
                "id = ?",
                arrayOf("corrupt"),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getBlob(0)
            }
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            database.update(
                "entries",
                ContentValues().apply { put("ciphertext", ciphertext) },
                "id = ?",
                arrayOf("corrupt"),
            )
        }

        try {
            SecureEntryRepository(context).use(SecureEntryRepository::list)
            fail("StorageUnavailableException expected")
        } catch (error: StorageUnavailableException) {
            assertTrue(error.canRecoverWithLocalReset)
            // Expected: authentication failure is reported as unavailable storage.
        }
    }

    @Test
    fun testMalformedDecryptedPayloadDoesNotAllowStorageReset() {
        SecureEntryRepository(context).use { repository ->
            repository.add(listOf(entry("malformed", "Malformed", "", byteArrayOf(4, 3, 2, 1))))
        }
        replaceEncryptedPayload("malformed", byteArrayOf(0, 8, 1, 2))

        try {
            SecureEntryRepository(context).use(SecureEntryRepository::list)
            fail("Storage read failure expected")
        } catch (error: StorageUnavailableException) {
            assertFalse(error.canRecoverWithLocalReset)
        } catch (_: Exception) {
            // Expected: malformed plaintext is a regular read/decode failure, not a key reset case.
        }
    }

    private fun entry(id: String, name: String, issuer: String, secret: ByteArray) = TotpEntry(
        id = id,
        displayName = name,
        issuer = issuer,
        accountName = "account",
        secret = secret,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun openDatabase(flags: Int): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(DATABASE_NAME).path,
        null,
        flags,
    )

    private fun clearStorage() {
        context.deleteDatabase(DATABASE_NAME)
        deleteKey()
    }

    private fun replaceEncryptedPayload(id: String, plaintext: ByteArray) {
        val key = KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .getKey(KEY_ALIAS, null) as SecretKey
        openDatabase(SQLiteDatabase.OPEN_READWRITE).use { database ->
            val row = database.query(
                "entries",
                arrayOf("revision", "schema_version"),
                "id = ?",
                arrayOf(id),
                null,
                null,
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getLong(0) to cursor.getInt(1)
            }
            val (revision, schema) = row
            val envelope = AesGcmEnvelopeCrypto(key).encrypt(
                "$id|$schema|$revision".toByteArray(Charsets.UTF_8),
                plaintext,
            )
            database.update(
                "entries",
                ContentValues().apply {
                    put("iv", envelope.iv)
                    put("ciphertext", envelope.ciphertext)
                },
                "id = ?",
                arrayOf(id),
            )
        }
    }

    private fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun ByteArray.contains(value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > size) return false
        return indices.take(size - value.size + 1).any { offset ->
            value.indices.all { index -> this[offset + index] == value[index] }
        }
    }

    companion object {
        private const val DATABASE_NAME = SecureEntryRepository.DATABASE_NAME
        private const val KEY_ALIAS = SecureEntryRepository.KEY_ALIAS
    }
}
