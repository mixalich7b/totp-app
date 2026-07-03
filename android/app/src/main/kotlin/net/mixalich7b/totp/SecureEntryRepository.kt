package net.mixalich7b.totp

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StorageUnavailableException(
    message: String,
    cause: Throwable? = null,
    val canRecoverWithLocalReset: Boolean = false,
) : Exception(message, cause)

@SuppressLint("UseKtx") // Explicit transactions keep revision updates and batch writes visibly atomic.
class SecureEntryRepository(context: Context) : AutoCloseable {
    private val database = EntryDatabase(context.applicationContext)
    private val crypto = EntryCrypto(database.readableDatabase)

    @Synchronized
    fun list(): List<TotpEntry> {
        val result = mutableListOf<TotpEntry>()
        try {
            database.readableDatabase.query(
                "entries", arrayOf("id", "revision", "schema_version", "iv", "ciphertext"),
                null, null, null, null, "rowid ASC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val revision = cursor.getLong(1)
                    val schema = cursor.getInt(2)
                    val plaintext = crypto.decrypt(id, revision, schema, cursor.getBlob(3), cursor.getBlob(4))
                    try {
                        result += EntryCodec.decode(plaintext).copy(id = id)
                    } finally {
                        plaintext.fill(0)
                    }
                }
            }
            return result
        } catch (error: Exception) {
            result.forEach { it.secret.fill(0) }
            throw error
        }
    }

    @Synchronized
    fun add(entries: List<TotpEntry>): Long = write(entries, replaceExisting = false)

    @Synchronized
    fun importEntries(entries: List<TotpEntry>): Long = write(entries, replaceExisting = true)

    @Synchronized
    fun update(entry: TotpEntry): Long {
        val db = database.writableDatabase
        var nextRevision: Long
        db.beginTransaction()
        try {
            nextRevision = revision(db) + 1
            val normalized = repositoryOwnedCopy(entry, System.currentTimeMillis())
            try {
                val plaintext = EntryCodec.encode(normalized)
                try {
                    val envelope = crypto.encrypt(
                        normalized.id,
                        nextRevision,
                        SCHEMA_VERSION,
                        plaintext,
                    )
                    val updated = db.update(
                        "entries",
                        ContentValues().apply {
                            put("revision", nextRevision)
                            put("schema_version", SCHEMA_VERSION)
                            put("iv", envelope.iv)
                            put("ciphertext", envelope.ciphertext)
                        },
                        "id = ?",
                        arrayOf(normalized.id),
                    )
                    check(updated == 1) { "Редактируемая запись не найдена" }
                } finally {
                    plaintext.fill(0)
                }
            } finally {
                normalized.secret.fill(0)
            }
            setRevision(db, nextRevision)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return nextRevision
    }

    private fun write(entries: List<TotpEntry>, replaceExisting: Boolean): Long {
        if (entries.isEmpty()) return revision()
        val db = database.writableDatabase
        var nextRevision: Long
        db.beginTransaction()
        try {
            nextRevision = revision(db) + 1
            entries.forEach { entry ->
                val normalized = repositoryOwnedCopy(entry, System.currentTimeMillis())
                try {
                    val plaintext = EntryCodec.encode(normalized)
                    try {
                        val envelope = crypto.encrypt(normalized.id, nextRevision, SCHEMA_VERSION, plaintext)
                        val values = ContentValues().apply {
                            put("id", normalized.id)
                            put("revision", nextRevision)
                            put("schema_version", SCHEMA_VERSION)
                            put("iv", envelope.iv)
                            put("ciphertext", envelope.ciphertext)
                        }
                        if (replaceExisting) {
                            val rowId = db.insertWithOnConflict(
                                "entries",
                                null,
                                values,
                                SQLiteDatabase.CONFLICT_REPLACE,
                            )
                            check(rowId != -1L) { "Не удалось сохранить импортированную запись" }
                        } else {
                            db.insertOrThrow("entries", null, values)
                        }
                    } finally {
                        plaintext.fill(0)
                    }
                } finally {
                    normalized.secret.fill(0)
                }
            }
            setRevision(db, nextRevision)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return nextRevision
    }

    @Synchronized
    fun delete(id: String): Long {
        val db = database.writableDatabase
        var nextRevision = 0L
        db.beginTransaction()
        try {
            val deleted = db.delete("entries", "id = ?", arrayOf(id))
            if (deleted == 0) return revision(db)
            nextRevision = revision(db) + 1
            setRevision(db, nextRevision)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return nextRevision
    }

    fun revision(): Long = revision(database.readableDatabase)

    private fun revision(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT value FROM metadata WHERE name = 'revision'", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun setRevision(db: SQLiteDatabase, value: Long) {
        db.execSQL("UPDATE metadata SET value = ? WHERE name = 'revision'", arrayOf(value))
    }

    override fun close() = database.close()

    companion object {
        private const val SCHEMA_VERSION = 1
        internal const val DATABASE_NAME = "totp_entries.db"
        internal const val KEY_ALIAS = "net.mixalich7b.totp.entries.v1"

        internal fun resetLocalStorage(context: Context) {
            val appContext = context.applicationContext
            val databasePath = appContext.getDatabasePath(DATABASE_NAME)
            if (databasePath.exists() && !appContext.deleteDatabase(DATABASE_NAME)) {
                throw StorageUnavailableException("Не удалось удалить локальное хранилище")
            }
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
            } catch (error: Exception) {
                throw StorageUnavailableException("Не удалось сбросить ключ локального хранилища", error)
            }
        }
    }
}

internal fun repositoryOwnedCopy(entry: TotpEntry, updatedAt: Long): TotpEntry =
    entry.copy(secret = entry.secret.copyOf(), updatedAt = updatedAt)

private class EntryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    SecureEntryRepository.DATABASE_NAME,
    null,
    1,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                id TEXT PRIMARY KEY NOT NULL,
                revision INTEGER NOT NULL,
                schema_version INTEGER NOT NULL,
                iv BLOB NOT NULL,
                ciphertext BLOB NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO metadata(name, value) VALUES ('revision', 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Для БД пока нет миграции $oldVersion -> $newVersion")
    }
}

internal data class Envelope(val iv: ByteArray, val ciphertext: ByteArray)

internal class AesGcmEnvelopeCrypto(private val key: SecretKey) {
    fun encrypt(aad: ByteArray, plaintext: ByteArray): Envelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv.copyOf()
        require(iv.size == 12) { "Криптопровайдер вернул некорректный GCM IV" }
        cipher.updateAAD(aad)
        return Envelope(iv, cipher.doFinal(plaintext))
    }

    fun decrypt(aad: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}

private class EntryCrypto(private val database: SQLiteDatabase) {
    private val key: SecretKey by lazy { loadOrCreateKey() }
    private val envelopeCrypto: AesGcmEnvelopeCrypto by lazy { AesGcmEnvelopeCrypto(key) }

    fun encrypt(id: String, revision: Long, schema: Int, plaintext: ByteArray): Envelope =
        envelopeCrypto.encrypt(aad(id, revision, schema), plaintext)

    fun decrypt(id: String, revision: Long, schema: Int, iv: ByteArray, ciphertext: ByteArray): ByteArray = try {
        envelopeCrypto.decrypt(aad(id, revision, schema), iv, ciphertext)
    } catch (error: Exception) {
        if (error.isLocalStorageKeyFailure()) {
            throw StorageUnavailableException(
                "Ключ Android Keystore отсутствует, повреждён или не подходит к локальным данным",
                error,
                canRecoverWithLocalReset = true,
            )
        }
        throw StorageUnavailableException("Не удалось прочитать локальное хранилище", error)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = try {
            keyStore.getKey(KEY_ALIAS, null)
        } catch (error: UnrecoverableKeyException) {
            throw StorageUnavailableException(
                "Ключ Android Keystore повреждён; существующие записи не восстановить",
                error,
                canRecoverWithLocalReset = true,
            )
        }
        (key as? SecretKey)?.let { return it }
        val hasRows = database.rawQuery("SELECT 1 FROM entries LIMIT 1", null).use { it.moveToFirst() }
        if (hasRows) {
            throw StorageUnavailableException(
                "Ключ Android Keystore отсутствует или не подходит к локальным данным",
                canRecoverWithLocalReset = true,
            )
        }
        return generateKey(preferStrongBox = true)
    }

    private fun generateKey(preferStrongBox: Boolean): SecretKey = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (Build.VERSION.SDK_INT >= 35) builder.setUnlockedDeviceRequired(true)
        if (preferStrongBox) builder.setIsStrongBoxBacked(true)
        generator.init(builder.build())
        generator.generateKey()
    } catch (error: StrongBoxUnavailableException) {
        generateKey(preferStrongBox = false)
    }

    private fun aad(id: String, revision: Long, schema: Int): ByteArray =
        "$id|$schema|$revision".toByteArray(Charsets.UTF_8)

    companion object {
        private const val KEY_ALIAS = SecureEntryRepository.KEY_ALIAS
    }
}

private fun Throwable.isLocalStorageKeyFailure(): Boolean =
    this is AEADBadTagException ||
        this is InvalidKeyException ||
        cause?.isLocalStorageKeyFailure() == true

private object EntryCodec {
    fun encode(entry: TotpEntry): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(entry.displayName)
            out.writeUTF(entry.issuer)
            out.writeUTF(entry.accountName)
            out.writeInt(entry.secret.size)
            out.write(entry.secret)
            out.writeInt(entry.algorithm.wireValue)
            out.writeInt(entry.digits)
            out.writeInt(entry.periodSeconds)
            out.writeLong(entry.createdAt)
            out.writeLong(entry.updatedAt)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): TotpEntry = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val name = input.readUTF()
        val issuer = input.readUTF()
        val account = input.readUTF()
        val secretLength = input.readInt()
        require(secretLength in 1..TotpEntry.MAX_SECRET_BYTES) { "Некорректная длина секрета" }
        val secret = ByteArray(secretLength).also(input::readFully)
        try {
            TotpEntry(
                displayName = name,
                issuer = issuer,
                accountName = account,
                secret = secret,
                algorithm = TotpAlgorithm.fromWire(input.readInt()),
                digits = input.readInt(),
                periodSeconds = input.readInt(),
                createdAt = input.readLong(),
                updatedAt = input.readLong(),
            )
        } catch (error: Exception) {
            secret.fill(0)
            throw error
        }
    }
}
